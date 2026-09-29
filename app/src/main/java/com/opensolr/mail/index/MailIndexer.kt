package com.opensolr.mail.index

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.auth.FastmailAuth
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Mailbox
import com.opensolr.mail.data.Message
import com.opensolr.mail.jmap.Html
import com.opensolr.mail.jmap.Jmap
import com.opensolr.mail.jmap.MailSync
import com.opensolr.mail.jmap.MailSync.Companion.strings
import com.opensolr.mail.net.IndexLimitException
import com.opensolr.mail.net.IndexMissingException
import com.opensolr.mail.net.OpensolrApi
import com.opensolr.mail.net.QuotaExceededException
import com.opensolr.mail.net.SignInRequiredException
import com.opensolr.mail.net.VectorNotAllowedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps the Opensolr Index in step with the mail: new messages in full (words + vector), moves and
 * flags as atomic updates, deletions, and a backfill that walks every message ever received, newest
 * first. Messages indexed without a vector (plan or monthly quota) get one later.
 */
class MailIndexer(private val context: Context) {

    enum class Outcome { DONE, MORE, RETRY }

    enum class Phase { IDLE, MAIL, HISTORY, ATTACHMENTS }

    private val db = MailDb.get(context)
    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)
    private val store = AccountStore.get(context)

    /** A batch that failed in this run: the run asks to be tried again instead of chaining straight on. */
    @Volatile private var batchFailed = false

    /** The text read out of each attachment blob in this run: a blob is downloaded and read once. */
    private val blobText = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun run(deadline: Long): Outcome = runLock.withLock {
        com.opensolr.mail.util.Diag.init(context)
        if (prefs.indexStopped || !prefs.signedIn) { com.opensolr.mail.util.Diag.log("MailIndexer", "run skipped: stopped=" + prefs.indexStopped + " signedIn=" + prefs.signedIn); return@withLock Outcome.DONE }
        runUntil = deadline
        com.opensolr.mail.util.Diag.log("MailIndexer", "run start, " + ((deadline - System.currentTimeMillis()) / 1000) + "s allowed, pending=" + db.indexPending())
        _status.value = _status.value.copy(running = true, error = null, pending = db.indexPending())
        var solrForCount: SolrClient? = null
        val outcome = try {
            val connection = try {
                MailIndex(context).ensure()
            } catch (e: IndexLimitException) {
                _status.value = _status.value.copy(running = false, noRoom = true, error = null)
                return@withLock Outcome.DONE
            } catch (e: com.opensolr.mail.net.IndexChoiceException) {
                _status.value = _status.value.copy(running = false, choices = e.choices, error = null)
                return@withLock Outcome.DONE
            }
            val solr = SolrClient(connection)
            solrForCount = solr
            refreshCounts(solr)
            _status.value = _status.value.copy(running = true)
            val name = connection.indexName
            runCatching { api.accountSummary(name, prefs.limits) }.getOrNull()?.let {
                prefs.limits = it
                prefs.vectorAllowed = it.vectorAllowed
                if (!it.aiFull && prefs.embedPausedUntil > System.currentTimeMillis()) prefs.embedPausedUntil = 0
                // Allowance already spent: no batch is sent only to be refused.
                if (it.vectorAllowed && it.aiFull) prefs.embedPausedUntil = nextMonth()
            }
            val sp = context.getSharedPreferences("index_status", Context.MODE_PRIVATE)
            // Stubs left by folder or flag updates on messages held once under another id: removed once.
            if (!sp.getBoolean("stubs_dropped", false)) { dropStubs(solr); sp.edit().putBoolean("stubs_dropped", true).commit() }
            if (sp.getInt("doc_version", 1) < DOC_VERSION || sp.getBoolean("reindex_all", false)) {
                db.clearIndexQueue()
                dropStrayCopies(solr)
                store.all().forEach { db.setState(it.key, MailSync.STATE_BACKFILL, "start") }
                sp.edit().putInt("doc_version", DOC_VERSION).putBoolean("reindex_all", false).commit()
            }

            var more = !indexMail(solr, name)
            com.opensolr.mail.util.Diag.log("MailIndexer", "mail pass done, more=" + more + " pending=" + db.indexPending())
            // Two accounts written side by side can each miss the other's copy in the same second: the copies go after the pass.
            runCatching { dropCopies(solr) }
            if (!more && System.currentTimeMillis() < runUntil) more = !fullBodyBackfill(solr)
            if (!more && System.currentTimeMillis() < runUntil) more = !vectorBackfill(solr, name)
            // Attachments never pass through the phone any more (api fetches and reads them), so no network gate.
            if (!more && System.currentTimeMillis() < runUntil) { more = !attachmentPass(solr, name); com.opensolr.mail.util.Diag.log("MailIndexer", "attachment pass done, more=" + more) }
            _status.value = _status.value.copy(error = null)
            // Work queued or accounts added while this pass was past the mail are taken by the next pass at once.
            val waiting = db.indexPending() > 0 || store.all().any { db.state(it.key, MailSync.STATE_BACKFILL).let { s -> s != null && s != "done" } }
            val out = when {
                batchFailed -> Outcome.RETRY
                more || waiting -> Outcome.MORE
                else -> Outcome.DONE
            }
            com.opensolr.mail.util.Diag.log("MailIndexer", "run end: " + out + " batchFailed=" + batchFailed + " more=" + more + " waiting=" + waiting + " timeLeft=" + ((runUntil - System.currentTimeMillis()) / 1000) + "s")
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            com.opensolr.mail.util.Diag.log("MailIndexer", "run cancelled", e)
            // Stopped by the system: not an error, the next run carries on where this one left off.
            _status.value = _status.value.copy(running = false, phase = Phase.IDLE, at = System.currentTimeMillis())
            persist()
            throw e
        } catch (e: SignInRequiredException) {
            com.opensolr.mail.util.Diag.log("MailIndexer", "run: sign-in required", e)
            _status.value = _status.value.copy(error = e.message)
            Outcome.DONE
        } catch (e: IndexMissingException) {
            com.opensolr.mail.util.Diag.log("MailIndexer", "run: index missing", e)
            MailIndex(context).forget()
            _status.value = _status.value.copy(error = e.message)
            Outcome.RETRY
        } catch (e: Exception) {
            com.opensolr.mail.util.Diag.log("MailIndexer", "run failed", e)
            _status.value = _status.value.copy(error = (e.message ?: e.javaClass.simpleName).take(300))
            Outcome.RETRY
        }
        refreshCounts(solrForCount)
        _status.value = _status.value.copy(running = false, phase = Phase.IDLE, at = System.currentTimeMillis())
        persist()
        outcome
    }

    /**
     * Starts the index over: the queue is dropped and every account's history is read again from the
     * newest message. With [wipe] the whole index is emptied first (every document, *:*). With
     * [restart] false indexing stays stopped afterwards. Waits for a running pass to end first.
     */
    suspend fun startOver(wipe: Boolean, restart: Boolean) = runLock.withLock {
        val connection = MailIndex(context).ensure()
        val solr = SolrClient(connection)
        if (wipe) solr.resetAll()
        db.clearIndexQueue()
        store.all().forEach { db.setState(it.key, MailSync.STATE_BACKFILL, "start") }
        // From now on only what is written again counts as done, so the progress of a reindex starts from zero.
        context.getSharedPreferences("index_status", Context.MODE_PRIVATE).edit().putInt("doc_version", DOC_VERSION).putBoolean("reindex_all", false)
            .putLong("rewrite_since", System.currentTimeMillis()).commit()
        prefs.indexStopped = !restart
        refreshCounts(solr)
        _status.value = _status.value.copy(running = false, phase = Phase.IDLE, error = null, at = System.currentTimeMillis())
        persist()
    }

    /**
     * Repair: every message of this phone's accounts still without a vector is written once more, so
     * its vector is asked for again. One pass only; what fails again stays marked and is not retried.
     */
    suspend fun queueMissingVectors(): Int {
        val connection = MailIndex(context).ensure()
        val solr = SolrClient(connection)
        val mine = localFilter() ?: return 0
        var cursor = "*"
        var queued = 0
        while (true) {
            val r = solr.select(listOf("q" to "*:*", "fq" to "vec_b:false", "fq" to mine.first, "acc" to mine.second,
                "fl" to "account_s,email_id_s", "sort" to "id asc", "rows" to "1000", "cursorMark" to cursor))
            val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: break
            (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }.forEach { (acc, list) ->
                localAccount(acc)?.let { a -> db.queueIndex(a.key, list.map { it.optString("email_id_s") }, MailSync.OP_UPSERT); queued += list.size }
            }
            val next = r.optString("nextCursorMark")
            if (next.isEmpty() || next == cursor) break
            cursor = next
        }
        prefs.indexStopped = false
        return queued
    }

    /** Documents in the index, how many carry a vector, what is still queued, and whether every account's history was walked. One faceted query. */
    /** The counts read again from the index, for the Refresh tool; false when the index cannot be reached. */
    suspend fun refreshStatus(): Boolean {
        if (!prefs.signedIn) return false
        val connection = runCatching { MailIndex(context).ensure() }.getOrNull() ?: return false
        refreshCounts(SolrClient(connection))
        _status.value = _status.value.copy(at = System.currentTimeMillis())
        persist()
        return true
    }

    private suspend fun refreshCounts(solr: SolrClient?) {
        val s = _status.value
        var indexed = s.indexed
        var meaning = s.withMeaning
        var attachments = s.attachmentsLeft
        var withAtt = s.indexedWithAtt
        var current = s.upToDate
        if (solr != null) runCatching {
            val mine = localFilter() ?: return
            // Done means written the current way and, after a reindex, written again since it started.
            val since = context.getSharedPreferences("index_status", Context.MODE_PRIVATE).getLong("rewrite_since", 0L)
            val doneQuery = "{!key=c}dv_i:$DOC_VERSION" + if (since > 0) " AND indexed_at_dt:[" + MailSync.iso(since) + " TO *]" else ""
            val r = solr.select(listOf("q" to "*:*", "fq" to mine.first, "acc" to mine.second, "rows" to "0", "facet" to "true", "facet.query" to "{!key=v}vec_b:true", "facet.query" to "{!key=a}att_todo_b:true", "facet.query" to "{!key=h}has_attachment_b:true", "facet.query" to doneQuery))
            indexed = r.getJSONObject("response").getLong("numFound")
            val fq = r.optJSONObject("facet_counts")?.optJSONObject("facet_queries")
            meaning = fq?.optLong("v") ?: meaning
            attachments = fq?.optLong("a") ?: attachments
            withAtt = fq?.optLong("h") ?: withAtt
            current = fq?.optLong("c") ?: current
        }
        val totals = mailboxTotals()
        val done = store.all().isNotEmpty() && store.all().all { db.state(it.key, MailSync.STATE_BACKFILL) == "done" }
        _status.value = _status.value.copy(
            indexed = indexed, withMeaning = meaning, attachmentsLeft = attachments, indexedWithAtt = withAtt, upToDate = current,
            mailTotal = totals?.first ?: _status.value.mailTotal, mailWithAtt = totals?.second ?: _status.value.mailWithAtt,
            pending = db.indexPending(), historyDone = done,
        )
    }

    /**
     * How many messages the accounts on this phone hold at Fastmail, and how many of them have
     * attachments, Notes left out as the indexer leaves them out. Null when Fastmail could not be asked.
     */
    private suspend fun mailboxTotals(): Pair<Long, Long>? = runCatching {
        var all = 0L
        var att = 0L
        for (account in store.all()) {
            val jmap = Jmap(context, account)
            val notes = notesBox(db.mailboxes(account.key))
            val base = notes?.let { JSONObject().put("inMailboxOtherThan", JSONArray().put(it.id)) }
            fun query(filter: JSONObject?) = JSONObject().put("accountId", jmap.accountId).put("calculateTotal", true).put("limit", 1)
                .apply { if (filter != null) put("filter", filter) }
            val withAttFilter = JSONObject().put("hasAttachment", true)
            val b = Jmap.Batch()
            val a = b.add("Email/query", query(base))
            val h = b.add("Email/query", query(if (base == null) withAttFilter else JSONObject().put("operator", "AND").put("conditions", JSONArray().put(base).put(withAttFilter))))
            val r = jmap.send(b)
            all += r.get(a).getLong("total")
            att += r.get(h).getLong("total")
        }
        all to att
    }.getOrNull()

    private fun persist() {
        val s = _status.value
        context.getSharedPreferences("index_status", Context.MODE_PRIVATE).edit()
            .putLong("indexed", s.indexed).putLong("meaning", s.withMeaning).putInt("pending", s.pending).putLong("attachments", s.attachmentsLeft)
            .putLong("with_att", s.indexedWithAtt).putLong("up_to_date", s.upToDate).putLong("mail_total", s.mailTotal).putLong("mail_with_att", s.mailWithAtt)
            .putBoolean("done", s.historyDone).putBoolean("no_room", s.noRoom).putString("error", s.error).putLong("at", s.at).apply()
    }

    /** The last known status, read once per process so the screens have numbers before the first run. */
    fun restore() {
        if (_status.value.at != 0L) return
        val sp = context.getSharedPreferences("index_status", Context.MODE_PRIVATE)
        _status.value = Status(
            indexed = sp.getLong("indexed", -1), withMeaning = sp.getLong("meaning", -1), pending = sp.getInt("pending", 0), attachmentsLeft = sp.getLong("attachments", -1),
            indexedWithAtt = sp.getLong("with_att", -1), upToDate = sp.getLong("up_to_date", -1), mailTotal = sp.getLong("mail_total", -1), mailWithAtt = sp.getLong("mail_with_att", -1),
            historyDone = sp.getBoolean("done", false), noRoom = sp.getBoolean("no_room", false), error = sp.getString("error", null), at = sp.getLong("at", 0),
        )
    }

    /**
     * Only the accounts on this phone: the index is shared by every phone of the Opensolr account,
     * and work on another phone's account can never be done here (it used to spin forever).
     */
    private fun localFilter(): Pair<String, String>? {
        val keys = store.all().map { indexKey(it) }
        return if (keys.isEmpty()) null else "{!terms f=account_s v=\$acc}" to keys.joinToString(",")
    }

    /** The account on this phone behind an index key, or null for an account only another phone has. */
    private fun localAccount(indexKey: String): MailAccount? = store.all().firstOrNull { indexKey(it) == indexKey }

    /** Copies of this phone's accounts written under an older id scheme or by another install: removed once. */
    private suspend fun dropStrayCopies(solr: SolrClient) {
        store.all().forEach { a ->
            runCatching {
                solr.deleteQuery("account_email_s:\"" + a.username.replace("\\", "\\\\").replace("\"", "\\\"") + "\" AND -account_s:" + indexKey(a))
            }
        }
    }

    /** Documents of this phone's accounts that carry only folder and flags, never written whole: gone. */
    private suspend fun dropStubs(solr: SolrClient) {
        val keys = store.all().map { indexKey(it) }.filter { it.matches(Regex("[0-9a-f]+")) }
        if (keys.isEmpty()) return
        solr.deleteQuery("(" + keys.joinToString(" OR ") { "id:" + it + "\\:*" } + ") AND NOT account_s:*")
    }

    /** A batch read from Fastmail and ready to be given a vector. */
    private class Prepared(
        val source: Source?,
        val account: MailAccount,
        val ids: List<String>,
        val entries: List<Pair<Message, MailSync.Body>>,
        val texts: List<String>,
        val attText: Map<String, String>,
        val attDone: Set<String>,
        val gone: List<String>,
        val boxes: Map<String, Mailbox>,
        val commitWithinMs: Int,
    )

    /** A batch with its documents built: all that is left is the write. */
    private class Written(
        val source: Source?,
        val account: MailAccount,
        val ids: List<String>,
        val docs: JSONArray,
        val gone: List<String>,
        val boxes: Map<String, Mailbox>,
        val commitWithinMs: Int,
    )

    /**
     * The queue of one account, handed out in batches. Everything that touches the queue, the
     * deletions, the flag changes and the walk through the history happens here, one lane at a time,
     * so several lanes can read from Fastmail at once without ever being given the same message twice.
     */
    private inner class Source(val account: MailAccount, val jmap: Jmap, val solr: SolrClient, val notes: Mailbox?) {
        private val gate = Mutex()
        private val buffer = ArrayDeque<String>()
        private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
        private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

        /** The next batch, or null when this account has nothing left at all. */
        suspend fun next(): List<String>? = gate.withLock {
            while (System.currentTimeMillis() < runUntil) {
                if (buffer.isNotEmpty()) {
                    val take = ArrayList<String>(batchSize())
                    while (buffer.isNotEmpty() && take.size < batchSize()) take += buffer.removeFirst()
                    inFlight += take
                    if (_status.value.phase != Phase.MAIL) _status.update { it.copy(phase = Phase.MAIL, pending = db.indexPending()) }
                    return@withLock take
                }
                val acc = account.key
                val deletes = db.indexBatch(acc, MailSync.OP_DELETE, 500)
                if (deletes.isNotEmpty()) {
                    indexWriteLock.withLock { solr.deleteIds(deletes.map { docId(account, it) }) }
                    db.indexDone(acc, deletes, MailSync.OP_DELETE)
                    continue
                }
                val meta = db.indexBatch(acc, MailSync.OP_META, 4000)
                if (meta.isNotEmpty()) {
                    // A move or a flag change seen at Fastmail: the documents' folder and flags are set at once, no rewrite.
                    setFolderAndFlags(solr, account, meta)
                    db.indexDone(acc, meta, MailSync.OP_META)
                    continue
                }
                // Rows written off in this run are read past, never around: the ask grows by as many.
                val queued = db.indexBatch(acc, MailSync.OP_UPSERT, REFILL + failed.size).filterNot { it in inFlight || it in failed }
                if (queued.isNotEmpty()) {
                    buffer += queued
                    continue
                }
                if (inFlight.isNotEmpty()) {
                    // Another lane holds the tail of the queue: the history is walked only once it is written,
                    // so a message in flight is never read a second time.
                    kotlinx.coroutines.delay(200)
                    continue
                }
                // Nothing queued and nothing in flight: the history is walked one page further.
                if (_status.value.phase != Phase.HISTORY) _status.update { it.copy(phase = Phase.HISTORY) }
                if (!backfillPage(jmap, acc, notes)) return@withLock null
            }
            null
        }

        /** The batch is in the index: its rows leave the queue. */
        fun done(ids: List<String>) {
            inFlight -= ids.toSet()
            db.indexDone(account.key, ids, MailSync.OP_UPSERT)
        }

        /** The batch goes back: its rows are handed out again, in this run. */
        fun release(ids: List<String>) {
            inFlight -= ids.toSet()
        }

        /** The batch failed: the rows stay queued for the next run, and are not tried again in this one. */
        fun giveUp(ids: List<String>) {
            inFlight -= ids.toSet()
            failed += ids
        }
    }

    /**
     * Indexes the mail of every account as a chain of three stages that run at the same time: lanes
     * that read from Fastmail, one that asks for the vectors (a single GPU answers, so asking for
     * more at once only queues there) and one that writes. A batch is never waited for twice.
     * True when every account has nothing left to do.
     */
    /**
     * Message-IDs already in the index with their vector (or nothing to embed), read once per pass in pages of 5000,
     * then kept in memory: a message another account of this phone, or another phone on a re-used index, already
     * wrote is not read from Fastmail again and never sent to the GPU a second time.
     */
    private suspend fun heldMessageIds(solr: SolrClient): MutableSet<String> {
        val out = java.util.Collections.synchronizedSet(HashSet<String>())
        val mine = localFilter() ?: return out
        val since = context.getSharedPreferences("index_status", Context.MODE_PRIVATE).getLong("rewrite_since", 0L)
        var cursor = "*"
        while (true) {
            val params = mutableListOf(
                "q" to "*:*", "fq" to mine.first, "acc" to mine.second, "fq" to "dv_i:$DOC_VERSION", "fq" to "vec_b:true OR vec_skip_b:true",
                "fl" to "message_id_s", "rows" to "5000", "sort" to "id asc", "cursorMark" to cursor,
            )
            if (since > 0) params += "fq" to "indexed_at_dt:[" + MailSync.iso(since) + " TO *]"
            val r = solr.select(params)
            val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: break
            for (i in 0 until docs.length()) docs.optJSONObject(i)?.optString("message_id_s")?.takeIf { it.isNotBlank() }?.let { out += it }
            val next = r.optString("nextCursorMark")
            if (docs.length() == 0 || next.isEmpty() || next == cursor) break
            cursor = next
        }
        return out
    }

    private suspend fun indexMail(solr: SolrClient, name: String): Boolean {
        val accounts = store.all()
        if (accounts.isEmpty()) return true
        val t0 = System.currentTimeMillis()
        val held = runCatching { heldMessageIds(solr) }.getOrElse { e -> com.opensolr.mail.util.Diag.log("MailIndexer", "held ids failed", e); java.util.Collections.synchronizedSet(HashSet()) }
        com.opensolr.mail.util.Diag.log("MailIndexer", "held message ids: " + held.size + " in " + (System.currentTimeMillis() - t0) + "ms")
        val leftOver = java.util.concurrent.atomic.AtomicBoolean(false)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            val prepared = Channel<Prepared>(PIPELINE_DEPTH)
            val writes = Channel<Written>(PIPELINE_DEPTH)

            val writer = launch {
                for (w in writes) {
                    try {
                        writeBatch(solr, w)
                        w.source?.done(w.ids)
                        _status.update { it.copy(pending = db.indexPending()) }
                        // A rewrite does not add a document, so the searchable count is read from the index, once a minute.
                        if (System.currentTimeMillis() - countedAt > 60_000L) { countedAt = System.currentTimeMillis(); refreshCounts(solr) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.opensolr.mail.util.Diag.log("MailIndexer", "write failed", e)
                        w.source?.giveUp(w.ids)
                        leftOver.set(true)
                        batchFailed = true
                        failures.incrementAndGet()
                    }
                }
            }

            val embedder = launch {
                for (p in prepared) {
                    try {
                        val w = embedBatch(name, p)
                        // From here the other account's lanes skip these messages.
                        p.entries.forEach { (m, _) -> if (m.messageId.isNotBlank()) held += m.messageId }
                        writes.send(w)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.opensolr.mail.util.Diag.log("MailIndexer", "vectors failed", e)
                        p.source?.giveUp(p.ids)
                        leftOver.set(true)
                        batchFailed = true
                        failures.incrementAndGet()
                    }
                }
                writes.close()
            }

            accounts.map { account ->
                launch {
                    val boxes = db.mailboxes(account.key).associateBy { it.id }
                    val source = Source(account, Jmap(context, account), solr, notesBox(boxes.values))
                    coroutineScope {
                        repeat(FETCH_LANES) {
                            launch {
                                while (true) {
                                    // Nothing answers any more (no network, the index is gone): the run stops here
                                    // instead of hammering until the deadline, and the system tries it again later.
                                    if (failures.get() >= MAX_FAILURES) { leftOver.set(true); return@launch }
                                    // Null is both "this account is done" and "the run is out of time": the clock says which.
                                    val ids = source.next()
                                    if (ids == null) {
                                        if (System.currentTimeMillis() >= runUntil) leftOver.set(true)
                                        return@launch
                                    }
                                    // Already in the index, with its vector, from another account or another phone: done without a read.
                                    val have = db.messages(account.key, ids).filter { it.messageId.isNotBlank() && it.messageId in held }.map { it.id }
                                    if (have.isNotEmpty()) source.done(have)
                                    val todo = if (have.isEmpty()) ids else ids.filterNot { it in have }
                                    if (todo.isEmpty()) continue
                                    try {
                                        prepared.send(prepareBatch(source.jmap, solr, account, todo, boxes, source.notes, source = source))
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: com.opensolr.mail.net.RateLimitedException) {
                                        // Fastmail asked for a pause: the batch goes back to the queue and this lane waits it out.
                                        source.release(todo)
                                        kotlinx.coroutines.delay(e.retryAfterSeconds.coerceIn(1, 60) * 1000L)
                                    } catch (e: Exception) {
                                        com.opensolr.mail.util.Diag.log("MailIndexer", "reading mail failed", e)
                                        source.giveUp(todo)
                                        leftOver.set(true)
                                        batchFailed = true
                                        failures.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                }
            }.joinAll()

            prepared.close()
            embedder.join()
            writer.join()
        }
        // Work handed back after a failure, or a lane that stopped on the deadline, is done by the next run.
        return !leftOver.get() && db.indexPending() == 0
    }

    /** How many messages one batch carries: without vectors to ask for, the reading is the only cost. */
    private fun batchSize(): Int = if (embeddingOff()) WORDS_BATCH else BATCH

    /** True when no vector can be asked for right now: plan, monthly quota, or the embedder is down. */
    private fun embeddingOff(): Boolean =
        !prefs.vectorAllowed || prefs.embedPausedUntil > System.currentTimeMillis() || embedDownUntil > System.currentTimeMillis()

    /** Queues the next page of the whole mailbox history. False when the history has been walked to the end. */
    private suspend fun backfillPage(jmap: Jmap, acc: String, notes: Mailbox?): Boolean {
        val anchor = db.state(acc, MailSync.STATE_BACKFILL) ?: return false
        if (anchor == "done") return false
        val args = JSONObject()
            .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false)))
            .put("limit", 500)
        notes?.let { args.put("filter", JSONObject().put("inMailboxOtherThan", JSONArray().put(it.id))) }
        if (anchor != "start") args.put("anchor", anchor).put("anchorOffset", 1)
        val r = try {
            jmap.call("Email/query", args)
        } catch (e: Jmap.JmapError) {
            if (e.type == "anchorNotFound") {
                db.setState(acc, MailSync.STATE_BACKFILL, "start")
                return true
            }
            throw e
        }
        val ids = r.getJSONArray("ids").strings()
        if (ids.isEmpty()) {
            db.setState(acc, MailSync.STATE_BACKFILL, "done")
            return false
        }
        db.queueIndex(acc, ids, MailSync.OP_UPSERT)
        db.setState(acc, MailSync.STATE_BACKFILL, ids.last())
        return true
    }

    /** Full documents for [ids]: headers and text from Fastmail, vectors from Opensolr, one write. */
    private suspend fun indexFull(jmap: Jmap, solr: SolrClient, name: String, account: MailAccount, ids: List<String>, boxes: Map<String, Mailbox>, notes: Mailbox?, fresh: Map<String, String> = emptyMap()) {
        val prepared = prepareBatch(jmap, solr, account, ids, boxes, notes, fresh)
        writeBatch(solr, embedBatch(name, prepared))
        db.indexDone(account.key, ids, MailSync.OP_UPSERT)
    }

    /**
     * Stage one: what the index already holds about these messages and what Fastmail says about them,
     * both asked for at the same time. Nothing is written here.
     */
    private suspend fun prepareBatch(
        jmap: Jmap, solr: SolrClient, account: MailAccount, ids: List<String>,
        boxes: Map<String, Mailbox>, notes: Mailbox?, fresh: Map<String, String> = emptyMap(), source: Source? = null,
    ): Prepared = coroutineScope {
        val acc = account.key
        // What the attachment pass already read survives a rewrite of the document.
        val keeping = async(Dispatchers.IO) {
            val kept = HashMap<String, String>()
            val readBefore = HashSet<String>()
            // Not read: the batch fails and waits, rather than rewriting the documents without their attachment text.
            run {
                val r0 = solr.select(listOf(
                    "q" to "*:*", "fq" to "{!terms f=id v=\$ids}", "ids" to ids.joinToString(",") { docId(account, it) },
                    "fl" to "email_id_s,attachment_text_t,att_todo_b", "rows" to ids.size.toString(),
                ))
                val arr = r0.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    val id = d.optString("email_id_s")
                    if (d.has("att_todo_b") && !d.optBoolean("att_todo_b")) readBefore += id
                    d.optString("attachment_text_t").takeIf { it.isNotBlank() }?.let { kept[id] = it }
                }
            }
            kept to readBefore
        }
        val reading = async(Dispatchers.IO) {
            jmap.call(
                "Email/get",
                JSONObject().put("ids", JSONArray(ids))
                    .put("properties", JSONArray(Jmap.HEADER_PROPS.strings() + listOf("textBody", "htmlBody", "attachments", "bodyValues")))
                    .put("fetchTextBodyValues", true)
                    .put("fetchHTMLBodyValues", true),
            )
        }
        val (kept, readBefore) = keeping.await()
        val list = reading.await().getJSONArray("list")
        val found = HashSet<String>()
        val entries = ArrayList<Pair<Message, MailSync.Body>>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val m = MailSync.parseHeader(acc, o)
            found += m.id
            if (notes != null && m.mailboxIds.all { it == notes.id }) continue
            entries += m to MailSync.parseBody(o)
        }
        // Only messages with a subject or a body get a vector; an empty one is sent nothing, not a placeholder.
        // A long one is cut down to what the embedding endpoint takes, so it still gets a vector.
        val texts = entries.map { (m, b) -> fitToEmbed(embedTextOf(m.received, m.subject, b.text)) }
        // A prepared batch waits in memory for its turn at the vectors, so it holds only what the
        // document keeps and nothing of the message as it arrived.
        Prepared(
            source = source,
            account = account,
            ids = ids,
            entries = entries.map { (m, b) -> m to b.copy(html = "") },
            texts = texts,
            attText = kept + fresh,
            attDone = readBefore + fresh.keys,
            gone = ids.filterNot { it in found },
            boxes = boxes,
            // While the history is still being walked, the index is told to commit far less often:
            // a message read from years ago is in no hurry to be searchable.
            commitWithinMs = if (db.state(acc, MailSync.STATE_BACKFILL).let { it != null && it != "done" }) HISTORY_COMMIT_MS else LIVE_COMMIT_MS,
        )
    }

    /** Stage two: the vectors of a prepared batch, and the documents built around them. */
    private suspend fun embedBatch(name: String, p: Prepared): Written {
        val sendable = p.texts.indices.filter { p.texts[it].trim().toByteArray().size >= 2 }
        val got = embed(name, sendable.map { p.texts[it] })
        val vectors = HashMap<Int, FloatArray>()
        val skip = p.texts.indices.filter { it !in sendable }.toMutableSet()
        if (got != null) sendable.forEachIndexed { j, i -> got[j]?.let { vectors[i] = it } ?: skip.add(i) }
        val docs = JSONArray()
        p.entries.forEachIndexed { i, (m, b) ->
            val o = doc(p.account, m, b, p.boxes, vectors[i])
            // Not asked again by the vector backfill: there is nothing to embed, or the embedder cannot take it.
            if (i in skip) o.put("vec_skip_b", true)
            p.attText[m.id]?.let { o.put("attachment_text_t", it.take(MAX_ATTACHMENT_TEXT)) }
            if (m.id in p.attDone) o.put("att_todo_b", false)
            docs.put(o)
        }
        return Written(p.source, p.account, p.ids, docs, p.gone, p.boxes, p.commitWithinMs)
    }

    /**
     * What the reader just did, written to the index at once: the messages are read back from Fastmail
     * as they are now and their documents rewritten (or removed, when they are gone for good), searchable
     * within a second. The queued rewrite of the same messages is dropped, so nothing is done twice.
     */
    suspend fun applyNow(account: MailAccount, ids: List<String>, gone: Boolean) {
        if (ids.isEmpty() || !prefs.signedIn) return
        val connection = MailIndex(context).ensure()
        val solr = SolrClient(connection)
        if (gone) {
            indexWriteLock.withLock { ids.chunked(4000).forEach { solr.deleteIds(it.map { id -> docId(account, id) }, LIVE_ACTION_MS) } }
        } else {
            setFolderAndFlags(solr, account, ids)
        }
        db.indexDone(account.key, ids, if (gone) MailSync.OP_DELETE else MailSync.OP_META)
    }

    /**
     * The folder and the flags of these messages set on their documents, nothing else touched: the vector and
     * the text stay. Messages this phone does not hold are read once from Fastmail, headers only, four thousand a call.
     */
    private suspend fun setFolderAndFlags(solr: SolrClient, account: MailAccount, ids: List<String>) {
        val boxes = db.mailboxes(account.key).associateBy { it.id }
        val missing = ids.filterNot { it in db.heldIds(account.key, ids) }
        val fetched = if (missing.isEmpty()) emptyList() else MailSync(context).getHeaders(Jmap(context, account), missing)
        if (fetched.isNotEmpty()) db.upsertMessages(fetched)
        // Read and written under the lock: nothing written from an older reading lands after this.
        indexWriteLock.withLock {
            val states = db.states(account.key, ids)
            states.entries.chunked(4000).forEach { chunk ->
                val docs = JSONArray()
                chunk.forEach { (id, st) -> docs.put(stateFields(JSONObject().put("id", docId(account, id)), st, boxes, set = true)) }
                solr.setExisting(docs, LIVE_ACTION_MS)
            }
        }
    }

    /** The folder and flag fields of a document; with [set] as an atomic update that leaves the rest of the document alone. */
    private fun stateFields(o: JSONObject, st: MailDb.MsgState, boxes: Map<String, Mailbox>, set: Boolean): JSONObject {
        fun v(x: Any): Any = if (set) JSONObject().put("set", x) else x
        return o
            .put("mailbox_ss", v(JSONArray(st.boxes.toList())))
            .put("mailbox_role_ss", v(JSONArray(st.boxes.mapNotNull { boxes[it]?.role })))
            .put("mailbox_name_ss", v(JSONArray(st.boxes.mapNotNull { boxes[it]?.name })))
            .put("seen_b", v(st.seen))
            .put("flagged_b", v(st.flagged))
            .put("answered_b", v(st.answered))
            .put("draft_b", v(st.draft))
    }

    /** A whole mailbox emptied for good: its documents leave the index at once. */
    suspend fun emptyBoxNow(account: MailAccount, boxId: String) {
        if (!prefs.signedIn) return
        val solr = SolrClient(MailIndex(context).ensure())
        // Only what lay in this mailbox alone is gone; a message also filed elsewhere just left it and is written again.
        val alone = ArrayList<String>()
        val elsewhere = ArrayList<String>()
        var cursor = "*"
        while (true) {
            val r = solr.select(listOf(
                "q" to "*:*", "fq" to "{!term f=account_s v=\$acc}", "acc" to indexKey(account), "fq" to "{!term f=mailbox_ss v=\$box}", "box" to boxId,
                "fl" to "email_id_s,mailbox_ss", "sort" to "id asc", "rows" to "1000", "cursorMark" to cursor,
            ))
            val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: break
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i)
                if ((d.optJSONArray("mailbox_ss")?.length() ?: 1) > 1) elsewhere += d.optString("email_id_s") else alone += d.optString("email_id_s")
            }
            val next = r.optString("nextCursorMark")
            if (next.isEmpty() || next == cursor) break
            cursor = next
        }
        alone.chunked(1000).forEach { solr.deleteIds(it.map { id -> docId(account, id) }, LIVE_ACTION_MS) }
        db.queueIndex(account.key, elsewhere, MailSync.OP_UPSERT)
    }

    /** Stage three: the one write of a batch, and the messages that are no longer at Fastmail. */
    private suspend fun writeBatch(solr: SolrClient, w: Written) = indexWriteLock.withLock {
        if (w.gone.isNotEmpty()) solr.deleteIds(w.gone.map { docId(w.account, it) })
        if (w.docs.length() > 0) {
            // A move or a flag made on this phone since the batch was read from Fastmail wins over what was read.
            val ids = (0 until w.docs.length()).map { w.docs.getJSONObject(it).optString("email_id_s") }
            val states = db.states(w.account.key, ids)
            for (i in 0 until w.docs.length()) {
                val o = w.docs.getJSONObject(i)
                states[o.optString("email_id_s")]?.let { stateFields(o, it, w.boxes, set = false) }
            }
            solr.add(w.docs, w.commitWithinMs)
        }
    }

    /** Two accounts write the same conversation when a reply crossed between them: of each Message-ID under this phone's accounts, only the first written stays. Runs after every pass. */
    private suspend fun dropCopies(solr: SolrClient) {
        val mine = store.all().map { indexKey(it) }
        if (mine.isEmpty()) return
        repeat(50) {
            val r = solr.select(listOf(
                "q" to "*:*", "fq" to "{!terms f=account_s v=\$accs}", "accs" to mine.joinToString(","),
                "rows" to "0", "facet" to "true", "facet.field" to "message_id_s", "facet.mincount" to "2", "facet.limit" to "500",
            ))
            val f = r.optJSONObject("facet_counts")?.optJSONObject("facet_fields")?.optJSONArray("message_id_s") ?: return
            val mids = (0 until f.length() step 2).map { f.getString(it) }.filter { it.isNotBlank() }
            if (mids.isEmpty()) return
            val d = solr.select(listOf(
                "q" to "{!terms f=message_id_s separator=\u0001 v=\$mids}", "mids" to mids.joinToString("\u0001"),
                "fq" to "{!terms f=account_s v=\$accs}", "accs" to mine.joinToString(","),
                "fl" to "id,message_id_s,indexed_at_dt", "rows" to (mids.size * mine.size).toString(),
            ))
            val arr = d.optJSONObject("response")?.optJSONArray("docs") ?: return
            val byMid = (0 until arr.length()).map { arr.getJSONObject(it) }.groupBy { it.optString("message_id_s") }
            val gone = byMid.values.filter { it.size > 1 }.flatMap { copies -> copies.sortedBy { it.optString("indexed_at_dt") }.drop(1).map { it.optString("id") } }
            if (gone.isEmpty()) return
            solr.deleteIds(gone)
            if (mids.size < 500) return
        }
    }

    /** Vectors for [texts], or null when the plan or the monthly quota says no; the documents then go in with words only. */
    private suspend fun embed(name: String, texts: List<String>): List<FloatArray?>? {
        if (texts.isEmpty() || !prefs.vectorAllowed || prefs.embedPausedUntil > System.currentTimeMillis() || embedDownUntil > System.currentTimeMillis()) return null
        return try {
            // Whole texts, never cut: a call carries at most 40 of them and about a million characters.
            val out = ArrayList<FloatArray?>(texts.size)
            var batch = ArrayList<String>()
            var chars = 0
            for (t in texts) {
                if (batch.isNotEmpty() && (batch.size >= BATCH || chars + t.length > 1_000_000)) {
                    out += api.batchEmbed(name, batch); batch = ArrayList(); chars = 0
                }
                batch += t; chars += t.length
            }
            if (batch.isNotEmpty()) out += api.batchEmbed(name, batch)
            out
        } catch (e: com.opensolr.mail.net.ServiceException) {
            // The embedder did not answer: the words go in now, the meaning is added by the vector backfill once it is back.
            com.opensolr.mail.util.Diag.log("MailIndexer", "embedding unavailable", e)
            embedDownUntil = System.currentTimeMillis() + EMBED_RETRY_MS
            null
        } catch (e: VectorNotAllowedException) {
            prefs.vectorAllowed = false
            null
        } catch (e: QuotaExceededException) {
            prefs.embedPausedUntil = nextMonth()
            null
        }
    }

    /** Documents written when the body was still cut are written again with the whole body. True when none are left. */
    private suspend fun fullBodyBackfill(solr: SolrClient): Boolean {
        val mine = localFilter() ?: return true
        val res = solr.select(listOf("q" to "*:*", "fq" to "size_l:[$OLD_BODY_CAP TO *]", "fq" to "-body_full_b:true", "fq" to mine.first, "acc" to mine.second, "fl" to "account_s,email_id_s", "rows" to "200", "sort" to "received_dt desc"))
        val docs = res.optJSONObject("response")?.optJSONArray("docs") ?: return true
        if (docs.length() == 0) return true
        (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }.forEach { (acc, list) ->
            localAccount(acc)?.let { a -> db.queueIndex(a.key, list.map { it.optString("email_id_s") }, MailSync.OP_UPSERT) }
        }
        return false
    }

    /** Documents written without a vector are written again once vectors can be made. True when none are left. */
    private suspend fun vectorBackfill(solr: SolrClient, name: String): Boolean {
        if (!prefs.vectorAllowed || prefs.embedPausedUntil > System.currentTimeMillis() || embedDownUntil > System.currentTimeMillis()) return true
        val mine = localFilter() ?: return true
        val res = solr.select(listOf("q" to "*:*", "fq" to "vec_b:false", "fq" to "-vec_skip_b:true", "fq" to mine.first, "acc" to mine.second, "fl" to "account_s,email_id_s", "rows" to "200", "sort" to "received_dt desc"))
        val docs = res.optJSONObject("response")?.optJSONArray("docs") ?: return true
        if (docs.length() == 0) return true
        (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }.forEach { (acc, list) ->
            localAccount(acc)?.let { a -> db.queueIndex(a.key, list.map { it.optString("email_id_s") }, MailSync.OP_UPSERT) }
        }
        return false
    }

    private fun doc(account: MailAccount, m: Message, body: MailSync.Body, boxes: Map<String, Mailbox>, vector: FloatArray?): JSONObject {
        // Day, month, weekday and hour are the phone's own local time, so a message at 01:56 is filed under that day, not the UTC one.
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = m.received }
        val text = body.text
        val o = JSONObject()
            .put("id", docId(account, m.id))
            .put("account_s", indexKey(account))
            .put("account_email_s", account.username)
            .put("email_id_s", m.id)
            .put("thread_id_s", m.threadId)
            .put("message_id_s", m.messageId)
            .put("has_attachment_b", body.attachments.any { !it.inline } || m.hasAttachment)
            .put("received_dt", MailSync.iso(m.received))
            .put("year_i", cal.get(Calendar.YEAR))
            .put("size_l", m.size)
            .put("subject_t", m.subject)
            .put("preview_t", m.preview)
            .put("body_t", text)
            .put("body_full_b", true)
            .put("indexed_at_dt", MailSync.iso(System.currentTimeMillis()))
        stateFields(o, MailDb.MsgState(m.mailboxIds.toSet(), m.seen, m.flagged, m.answered, m.draft), boxes, set = false)
        m.sender?.let {
            o.put("from_s", it.email.lowercase()).put("from_name_s", it.name).put("from_t", (it.name + " " + it.email).trim())
        }
        if (m.to.isNotEmpty()) o.put("to_ss", JSONArray(m.to.map { it.email.lowercase() })).put("to_tm", JSONArray(m.to.map { (it.name + " " + it.email).trim() }))
        if (m.cc.isNotEmpty()) o.put("cc_ss", JSONArray(m.cc.map { it.email.lowercase() })).put("cc_tm", JSONArray(m.cc.map { (it.name + " " + it.email).trim() }))
        val files = body.attachments.filter { !it.inline && it.name.isNotBlank() }
        if (files.isNotEmpty()) {
            o.put("attachment_names_tm", JSONArray(files.map { it.name }))
            o.put("attachment_types_ss", JSONArray(files.map { it.type.lowercase() }.distinct()))
            o.put("attachment_ext_ss", JSONArray(files.mapNotNull { f -> f.name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..6 } }.distinct()))
        }
        o.put("attachment_count_i", files.size)
        o.put("att_todo_b", body.attachments.any { readable(it) })
        val people = (m.from + m.to + m.cc).map { it.name.ifBlank { it.email }.trim() }.filter { it.isNotBlank() }.distinct()
        if (people.isNotEmpty()) o.put("people_ss", JSONArray(people))
        val domains = (m.from + m.to + m.cc).mapNotNull { domainOf(it.email) }.distinct()
        if (domains.isNotEmpty()) o.put("domains_ss", JSONArray(domains))
        m.sender?.email?.let { domainOf(it) }?.let { o.put("from_domain_s", it) }
        m.sender?.let { o.put("from_label_s", labelOf(it)) }
        val receivers = (m.to + m.cc).map { labelOf(it) }.distinct()
        if (receivers.isNotEmpty()) o.put("to_label_ss", JSONArray(receivers))
        o.put("month_s", String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1))
        o.put("day_s", String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)))
        // The date in words ("06 March 2025", the phone's own time), searched like any text: "march 2025" finds it.
        o.put("date_t", java.text.SimpleDateFormat("dd MMMM yyyy", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(m.received))
        o.put("weekday_i", cal.get(Calendar.DAY_OF_WEEK))
        o.put("hour_i", cal.get(Calendar.HOUR_OF_DAY))
        if (vector != null) o.put(VECTOR, JSONArray(vector.toList())).put("vec_b", true) else o.put("vec_b", false)
        // Which way of indexing wrote it: a rewrite after a change counts as work left until it is done.
        o.put("dv_i", DOC_VERSION)
        return o
    }

    /**
     * Reads what is inside the attachments, on api.opensolr.com: pictures through OCR,
     * documents through doc_to_text. The text goes into the document and into its vector.
     * True when nothing is left to read (or the documents endpoint is not live yet).
     */
    private suspend fun attachmentPass(solr: SolrClient, name: String): Boolean {
        if (prefs.embedPausedUntil > System.currentTimeMillis()) return true
        // A cursor over the messages still to read, newest first: what this pass just rewrote is not seen again
        // while its commit is still on the way, and every page is 20 messages, the size of one call to the box.
        var cursor = "*"
        while (System.currentTimeMillis() < runUntil) {
            // new mail waits for no attachment: the run hands over and the next one indexes it first
            if (db.indexPending() > 0) return false
            val mine = localFilter() ?: return true
            val res = solr.select(listOf("q" to "*:*", "fq" to "att_todo_b:true", "fq" to mine.first, "acc" to mine.second, "rows" to "20", "sort" to "received_dt desc, id asc", "cursorMark" to cursor, "fl" to "account_s,email_id_s"))
            val docs = res.optJSONObject("response")?.optJSONArray("docs") ?: return true
            val left = res.optJSONObject("response")?.optLong("numFound") ?: 0L
            // Nothing to read is not a stage at work: the phase moves only when there is something left.
            _status.value = if (left > 0) _status.value.copy(phase = Phase.ATTACHMENTS, attachmentsLeft = left) else _status.value.copy(attachmentsLeft = left)
            com.opensolr.mail.util.Diag.log("MailIndexer", "attachments: " + left + " left, batch of " + docs.length())
            if (docs.length() == 0) return true
            val next = res.optString("nextCursorMark")
            if (next.isEmpty() || next == cursor) return true
            cursor = next
            val byAcc = (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }
            for ((acc, list) in byAcc) {
                val account = localAccount(acc) ?: continue
                val ids = list.map { it.optString("email_id_s") }
                val fresh = try {
                    readAttachments(account, name, ids)
                } catch (e: com.opensolr.mail.net.EndpointMissingException) {
                    com.opensolr.mail.util.Diag.log("MailIndexer", "attachments: endpoint missing", e)
                    return true
                } catch (e: QuotaExceededException) {
                    // The month's allowance is spent: the attachments wait for the next month, still marked to do.
                    prefs.embedPausedUntil = nextMonth()
                    return true
                } catch (e: VectorNotAllowedException) {
                    return true
                }
                val boxes = db.mailboxes(account.key).associateBy { it.id }
                indexFull(Jmap(context, account), solr, name, account, fresh.keys.toList(), boxes, notesBox(boxes.values), fresh)
            }
        }
        return false
    }

    /** The text of the readable attachments of several messages (one Email/get for all of them), read on api.opensolr.com, which fetches them from Fastmail itself. */
    private suspend fun readAttachments(account: MailAccount, index: String, emailIds: List<String>): Map<String, String> {
        val jmap = Jmap(context, account)
        val r = jmap.call("Email/get", JSONObject().put("ids", JSONArray(emailIds)).put("properties", JSONArray(listOf("id", "attachments"))))
        val list = r.getJSONArray("list")
        val perMessage = (0 until list.length()).associate { i ->
            val o = list.getJSONObject(i)
            o.getString("id") to MailSync.parseBody(o.put("bodyValues", JSONObject())).attachments.filter { readable(it) }.take(10)
        }
        val texts = HashMap<String, StringBuilder>()
        emailIds.forEach { texts[it] = StringBuilder() }
        // One blob is read once, however many messages carry it (a file quoted along a thread, the same mail under
        // two accounts): its text is kept for the run and copied to every message that has it.
        val carriers = HashMap<String, MutableList<Pair<String, com.opensolr.mail.data.Attachment>>>()
        perMessage.forEach { (id, atts) -> atts.forEach { a -> carriers.getOrPut(a.blobId) { ArrayList() } += id to a } }
        fun deliver(blobId: String, text: String) {
            blobText[blobId] = text
            carriers[blobId]?.forEach { (id, a) -> texts[id]?.append(a.name)?.append(":\n")?.append(text)?.append("\n\n") }
        }
        carriers.keys.filter { blobText.containsKey(it) }.forEach { b -> blobText[b]?.let { t -> if (t.isNotEmpty()) deliver(b, t) } }
        val todo = carriers.filterKeys { !blobText.containsKey(it) }.map { (_, list) -> list.first().second }
        // Twenty to a call: the box fetches them from Fastmail ten at a time and reads them; nothing passes through here.
        todo.chunked(20).forEach { chunk ->
            val token = FastmailAuth.accessToken(context, account.key)
            val jmapId = account.jmapAccountId.ifBlank { runCatching { FastmailAuth.fetchSession(token).getJSONObject("primaryAccounts").optString("urn:ietf:params:jmap:mail") }.getOrDefault("") }
            // A server error or a dropped connection is tried once more after a pause; what still fails is skipped, the run goes on.
            val read: List<String?> = try {
                api.mailAttachments(index, jmapId, account.downloadUrl, token, chunk)
            } catch (e: com.opensolr.mail.net.EndpointMissingException) {
                throw e
            } catch (e: QuotaExceededException) {
                throw e
            } catch (e: VectorNotAllowedException) {
                throw e
            } catch (e: java.io.IOException) {
                kotlinx.coroutines.delay(3_000L)
                try {
                    api.mailAttachments(index, jmapId, account.downloadUrl, token, chunk)
                } catch (e2: com.opensolr.mail.net.EndpointMissingException) {
                    throw e2
                } catch (e2: QuotaExceededException) {
                    throw e2
                } catch (e2: VectorNotAllowedException) {
                    throw e2
                } catch (e2: java.io.IOException) {
                    com.opensolr.mail.util.Diag.log("MailIndexer", "attachments failed", e2)
                    chunk.map { null }
                }
            }
            chunk.forEachIndexed { i, a -> deliver(a.blobId, read.getOrNull(i).orEmpty()) }
        }
        return texts.mapValues { it.value.toString().trim() }
    }

    private fun notesBox(boxes: Collection<Mailbox>): Mailbox? = boxes.firstOrNull { it.parentId == null && it.role == null && it.name.equals("Notes", true) }

    private fun nextMonth(): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 5)
    }.timeInMillis

    /** What the settings and the mailbox list show about the index. */
    data class Status(
        val running: Boolean = false,
        val indexed: Long = -1,
        val withMeaning: Long = -1,
        val pending: Int = 0,
        /** Indexed messages whose attachments are still to be read into text. */
        val attachmentsLeft: Long = -1,
        /** Messages written the current way; the rest are still to be written again. */
        val upToDate: Long = -1,
        /** Indexed messages that have attachments. */
        val indexedWithAtt: Long = -1,
        /** Messages at Fastmail, and those of them with attachments: what the whole job is measured against. */
        val mailTotal: Long = -1,
        val mailWithAtt: Long = -1,
        /** What the running indexer is busy with right now. */
        val phase: Phase = Phase.IDLE,
        val historyDone: Boolean = false,
        val noRoom: Boolean = false,
        /** Mail indexes of other phones the reader can re-use; the indexer waits until one is chosen or declined. */
        val choices: List<com.opensolr.mail.net.OpensolrApi.IndexInfo> = emptyList(),
        val error: String? = null,
        val at: Long = 0,
    ) {
        /** Messages at Fastmail not yet in the index as they should be, or still queued to be written (a repair). */
        val messagesLeft: Long get() = when {
            // History walked and nothing queued: done, whatever Fastmail's total says (copies of one message across accounts are kept once).
            historyDone && pending == 0 -> 0
            mailTotal < 0 || upToDate < 0 -> -1
            else -> maxOf(mailTotal - upToDate, pending.toLong()).coerceAtLeast(0)
        }

        /**
         * Messages whose attachments are still to be read: those in the index plus those with attachments not indexed yet.
         * History walked and nothing queued: only the index says what is left (a copy of a message is held once, while
         * Fastmail counts every copy, so its total is never reached).
         */
        val attLeft: Long get() = when {
            attachmentsLeft < 0 -> -1
            (historyDone && pending == 0) || mailWithAtt < 0 || indexedWithAtt < 0 -> attachmentsLeft
            else -> attachmentsLeft + (mailWithAtt - indexedWithAtt).coerceAtLeast(0)
        }

        /** Messages done of all at Fastmail; null until the totals are known. */
        val messageProgress: Pair<Long, Long>? get() =
            if (messagesLeft < 0 || mailTotal <= 0) null else (mailTotal - messagesLeft).coerceAtLeast(0) to mailTotal

        /** Messages with attachments whose attachments are done, of all with attachments; null until known. */
        val attachmentProgress: Pair<Long, Long>? get() =
            if (attLeft < 0 || mailWithAtt <= 0) null else (mailWithAtt - attLeft.coerceAtMost(mailWithAtt)) to mailWithAtt
    }

    companion object {
        const val VECTOR = "embeddings_vec"
        /** Every write of folder or flag state to the index, full document or atomic, goes through this lock, in order. */
        private val indexWriteLock = Mutex()
        /** How soon a write made for an action of the reader is searchable. */
        private const val LIVE_ACTION_MS = 500
        const val DOC_VERSION = 9
        /** The most one text may weigh at batch_embed; a longer one is cut to it. Three to five times
         *  the embedder's own window of 512 tokens, so the vector is the same one it would have made
         *  of the whole text, in any language, without carrying the rest over the wire. */
        private const val MAX_EMBED_BYTES = 8_000

        /** [text] at its first [MAX_EMBED_BYTES] bytes, cut between characters, never inside one. */
        fun fitToEmbed(text: String): String {
            val bytes = text.toByteArray()
            if (bytes.size <= MAX_EMBED_BYTES) return text
            var end = MAX_EMBED_BYTES
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return String(bytes, 0, end, Charsets.UTF_8)
        }
        private const val BATCH = 40

        /** Without vectors to wait for, a batch carries more messages; the body asked for never changes,
         *  so the same text is written to the index either way. */
        private const val WORDS_BATCH = 100

        /** Lanes reading from Fastmail at the same time, per account; the vectors and the write have one each. */
        private const val FETCH_LANES = 2

        /** Batches waiting between two stages: enough to keep them busy, few enough to stay small in memory. */
        private const val PIPELINE_DEPTH = 1

        /** Rows taken out of the queue into memory in one go, then handed to the lanes in batches. */
        private const val REFILL = 500

        /** Failed batches after which a run gives up and lets the system start it again later. */
        private const val MAX_FAILURES = 5

        /** How long the index may wait before a write becomes searchable: a walk through old mail is in no hurry. */
        private const val LIVE_COMMIT_MS = 5_000
        private const val HISTORY_COMMIT_MS = 60_000

        /** Older documents kept at most this many characters of the body; only a message at least this large could have been cut. */
        private const val OLD_BODY_CAP = 30_000
        private const val MAX_ATTACHMENT_TEXT = 100_000

        private val runLock = Mutex()
        @Volatile private var countedAt = 0L
        private const val EMBED_RETRY_MS = 10 * 60_000L
        @Volatile private var embedDownUntil = 0L
        @Volatile private var runUntil = 0L

        /** Lets a run that got its foreground notification go on longer than the background limit. */
        fun extendRun(until: Long) {
            if (until > runUntil) runUntil = until
        }

        /** Ends a running pass at its next batch, so a start-over waiting for it gets the index at once. */
        fun stopRun() { runUntil = 0L }

        /** Runs [block] with no indexing pass in the middle of it. */
        suspend fun <T> exclusive(block: suspend () -> T): T = runLock.withLock { block() }
        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        /** The reader answered the re-use question. */
        fun clearChoices() = _status.update { it.copy(choices = emptyList()) }

        /**
         * The account as the shared index knows it: its Fastmail mailbox, not the address it was signed in with.
         * The same mailbox on any phone writes one copy of each message, and two different mailboxes never mix,
         * whatever addresses they were signed in with.
         */
        fun indexKey(account: MailAccount): String =
            if (account.jmapAccountId.isBlank()) indexKeyOf(account.username) else indexKeyOf("jmap:" + account.jmapAccountId)

        fun indexKeyOf(username: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(username.trim().lowercase().toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }

        fun docId(account: MailAccount, emailId: String) = indexKey(account) + ":" + emailId


        /** "Name (address)", or the bare address: the same person on ten addresses stays ten entries. */
        fun labelOf(a: com.opensolr.mail.data.Address): String {
            val email = a.email.trim().lowercase()
            val name = a.name.trim().trim('"', '\'').takeIf { it.isNotBlank() && !it.equals(email, true) }
            return if (name != null) "$name ($email)" else email
        }

        fun domainOf(email: String): String? = email.substringAfter('@', "").lowercase().trim().takeIf { it.contains('.') }

        /**
         * What the attachment pass reads, and nothing else: pictures (any format the phone decodes,
         * sent as a 1024 px copy) and text documents (PDF, Word, RTF, OpenDocument text, plain text,
         * HTML), 20 MB at most. Archives, spreadsheets, presentations and anything unknown are never read.
         */
        fun readable(a: com.opensolr.mail.data.Attachment): Boolean {
            if (a.inline || a.size <= 0 || a.size > MAX_ATTACHMENT_BYTES) return false
            val type = a.type.lowercase().substringBefore(';').trim()
            val ext = a.name.substringAfterLast('.', "").lowercase()
            if (isImage(a)) return true
            if (type.isNotEmpty() && type != "application/octet-stream" && type !in DOC_TYPES) return false
            return ext in DOC_EXT || type in DOC_TYPES
        }

        fun isImage(a: com.opensolr.mail.data.Attachment): Boolean {
            val type = a.type.lowercase().substringBefore(';').trim()
            val ext = a.name.substringAfterLast('.', "").lowercase()
            return (type.startsWith("image/") && type != "image/svg+xml") || (type == "application/octet-stream" && ext in IMAGE_EXT)
        }

        private const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")
        private val DOC_EXT = setOf("pdf", "doc", "docx", "rtf", "odt", "txt", "html", "htm")
        private val DOC_TYPES = setOf(
            "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf", "text/rtf", "application/vnd.oasis.opendocument.text", "text/plain", "text/html",
        )

        /**
         * What the vector is made of: the date the message came, as "August 05 2026" in the phone's own
         * time, then the subject and the whole body, all plain text. Empty when the message has neither
         * subject nor body: a date alone is never sent.
         */
        fun embedTextOf(received: Long, subject: String, body: String): String {
            val sub = Html.plain(subject)
            val text = Html.plain(body)
            val content = when {
                sub.isEmpty() -> text
                text.isEmpty() -> sub
                else -> sub + "\n\n" + text
            }
            if (content.isEmpty()) return ""
            if (received <= 0L) return content
            val date = java.text.SimpleDateFormat("MMMM dd yyyy", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(received)
            return date + "\n\n" + content
        }
    }
}
