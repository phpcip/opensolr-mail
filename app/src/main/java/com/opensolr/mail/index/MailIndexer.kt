package com.opensolr.mail.index

import android.content.Context
import com.opensolr.mail.data.AccountStore
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

    suspend fun run(deadline: Long): Outcome = runLock.withLock {
        if (prefs.indexStopped) return@withLock Outcome.DONE
        runUntil = deadline
        _status.value = _status.value.copy(running = true, error = null, pending = db.indexPending())
        var solrForCount: SolrClient? = null
        val outcome = try {
            val connection = try {
                MailIndex(context).ensure()
            } catch (e: IndexLimitException) {
                _status.value = _status.value.copy(running = false, noRoom = true, error = null)
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
            }
            val sp = context.getSharedPreferences("index_status", Context.MODE_PRIVATE)
            if (sp.getInt("doc_version", 1) < DOC_VERSION || sp.getBoolean("reindex_all", false)) {
                db.clearIndexQueue()
                dropStrayCopies(solr)
                store.all().forEach { db.setState(it.key, MailSync.STATE_BACKFILL, "start") }
                sp.edit().putInt("doc_version", DOC_VERSION).putBoolean("reindex_all", false).commit()
            }

            var more = !indexMail(solr, name)
            if (!more && System.currentTimeMillis() < runUntil) more = !vectorBackfill(solr, name)
            if (!more && System.currentTimeMillis() < runUntil && unmetered()) more = !attachmentPass(solr, name)
            _status.value = _status.value.copy(error = null)
            when {
                batchFailed -> Outcome.RETRY
                more -> Outcome.MORE
                else -> Outcome.DONE
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the system: not an error, the next run carries on where this one left off.
            _status.value = _status.value.copy(running = false, phase = Phase.IDLE, at = System.currentTimeMillis())
            persist()
            throw e
        } catch (e: SignInRequiredException) {
            _status.value = _status.value.copy(error = e.message)
            Outcome.DONE
        } catch (e: IndexMissingException) {
            MailIndex(context).forget()
            _status.value = _status.value.copy(error = e.message)
            Outcome.RETRY
        } catch (e: Exception) {
            android.util.Log.w("MailIndexer", "run failed", e)
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
        context.getSharedPreferences("index_status", Context.MODE_PRIVATE).edit().putInt("doc_version", DOC_VERSION).putBoolean("reindex_all", false).commit()
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
    private suspend fun refreshCounts(solr: SolrClient?) {
        val s = _status.value
        var indexed = s.indexed
        var meaning = s.withMeaning
        var attachments = s.attachmentsLeft
        var withAtt = s.indexedWithAtt
        var current = s.upToDate
        if (solr != null) runCatching {
            val mine = localFilter() ?: return
            val r = solr.select(listOf("q" to "*:*", "fq" to mine.first, "acc" to mine.second, "rows" to "0", "facet" to "true", "facet.query" to "{!key=v}vec_b:true", "facet.query" to "{!key=a}att_todo_b:true", "facet.query" to "{!key=h}has_attachment_b:true", "facet.query" to "{!key=c}dv_i:$DOC_VERSION"))
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
                    solr.deleteIds(deletes.map { docId(account, it) })
                    db.indexDone(acc, deletes)
                    continue
                }
                val meta = db.indexBatch(acc, MailSync.OP_META, 200)
                if (meta.isNotEmpty()) {
                    applyMeta(acc, meta)
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
            db.indexDone(account.key, ids)
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
    private suspend fun indexMail(solr: SolrClient, name: String): Boolean {
        val accounts = store.all()
        if (accounts.isEmpty()) return true
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
                        android.util.Log.w("MailIndexer", "write failed", e)
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
                        writes.send(embedBatch(name, p))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("MailIndexer", "vectors failed", e)
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
                                    try {
                                        prepared.send(prepareBatch(source.jmap, solr, account, ids, boxes, source.notes, source = source))
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: com.opensolr.mail.net.RateLimitedException) {
                                        // Fastmail asked for a pause: the batch goes back to the queue and this lane waits it out.
                                        source.release(ids)
                                        kotlinx.coroutines.delay(e.retryAfterSeconds.coerceIn(1, 60) * 1000L)
                                    } catch (e: Exception) {
                                        android.util.Log.w("MailIndexer", "reading mail failed", e)
                                        source.giveUp(ids)
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
        db.indexDone(account.key, ids)
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
        // Without a vector to ask for, the body is read only as far as the document keeps it.
        val bodyBytes = if (embeddingOff()) WORDS_BODY_BYTES else FULL_BODY_BYTES
        // What the attachment pass already read survives a rewrite of the document.
        val keeping = async(Dispatchers.IO) {
            val kept = HashMap<String, String>()
            val readBefore = HashSet<String>()
            runCatching {
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
                    .put("fetchHTMLBodyValues", true)
                    .put("maxBodyValueBytes", bodyBytes),
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
        val texts = entries.map { (m, b) -> fitToEmbed(embedTextOf(m.subject, b.text)) }
        // A prepared batch waits in memory for its turn at the vectors, so it holds only what the
        // document keeps and nothing of the message as it arrived.
        Prepared(
            source = source,
            account = account,
            ids = ids,
            entries = entries.map { (m, b) -> m to b.copy(text = b.text.take(MAX_BODY), html = "") },
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
        return Written(p.source, p.account, p.ids, docs, p.gone, p.commitWithinMs)
    }

    /** Stage three: the one write of a batch, and the messages that are no longer at Fastmail. */
    private suspend fun writeBatch(solr: SolrClient, w: Written) {
        if (w.gone.isNotEmpty()) solr.deleteIds(w.gone.map { docId(w.account, it) })
        if (w.docs.length() > 0) solr.add(w.docs, w.commitWithinMs)
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
            android.util.Log.w("MailIndexer", "embedding unavailable", e)
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

    /** Moves and flag changes: the vector is not stored, so an atomic update would lose it; the document is written again in full. */
    private fun applyMeta(acc: String, ids: List<String>) {
        db.indexDone(acc, ids)
        db.queueIndex(acc, ids, MailSync.OP_UPSERT)
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
        val text = body.text.take(MAX_BODY)
        val o = JSONObject()
            .put("id", docId(account, m.id))
            .put("account_s", indexKey(account))
            .put("account_email_s", account.username)
            .put("email_id_s", m.id)
            .put("thread_id_s", m.threadId)
            .put("message_id_s", m.messageId)
            .put("mailbox_ss", JSONArray(m.mailboxIds.toList()))
            .put("mailbox_role_ss", JSONArray(m.mailboxIds.mapNotNull { boxes[it]?.role }))
            .put("mailbox_name_ss", JSONArray(m.mailboxIds.mapNotNull { boxes[it]?.name }))
            .put("seen_b", m.seen)
            .put("flagged_b", m.flagged)
            .put("answered_b", m.answered)
            .put("draft_b", m.draft)
            .put("has_attachment_b", body.attachments.any { !it.inline } || m.hasAttachment)
            .put("received_dt", MailSync.iso(m.received))
            .put("year_i", cal.get(Calendar.YEAR))
            .put("size_l", m.size)
            .put("subject_t", m.subject)
            .put("preview_t", m.preview)
            .put("body_t", text)
            .put("indexed_at_dt", MailSync.iso(System.currentTimeMillis()))
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
        o.put("weekday_i", cal.get(Calendar.DAY_OF_WEEK))
        o.put("hour_i", cal.get(Calendar.HOUR_OF_DAY))
        if (vector != null) o.put(VECTOR, JSONArray(vector.toList())).put("vec_b", true) else o.put("vec_b", false)
        // Which way of indexing wrote it: a rewrite after a change counts as work left until it is done.
        o.put("dv_i", DOC_VERSION)
        return o
    }

    private fun unmetered(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }

    /**
     * Reads what is inside the attachments, on unmetered networks only: pictures through OCR,
     * documents through doc_to_text. The text goes into the document and into its vector.
     * True when nothing is left to read (or the documents endpoint is not live yet).
     */
    private suspend fun attachmentPass(solr: SolrClient, name: String): Boolean {
        while (System.currentTimeMillis() < runUntil) {
            val mine = localFilter() ?: return true
            val res = solr.select(listOf("q" to "*:*", "fq" to "att_todo_b:true", "fq" to mine.first, "acc" to mine.second, "rows" to "5", "sort" to "received_dt desc", "fl" to "account_s,email_id_s"))
            val docs = res.optJSONObject("response")?.optJSONArray("docs") ?: return true
            val left = res.optJSONObject("response")?.optLong("numFound") ?: 0L
            _status.value = _status.value.copy(phase = Phase.ATTACHMENTS, attachmentsLeft = left)
            if (docs.length() == 0) return true
            val byAcc = (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }
            for ((acc, list) in byAcc) {
                val account = localAccount(acc) ?: continue
                val ids = list.map { it.optString("email_id_s") }
                val fresh = try {
                    readAttachments(account, name, ids)
                } catch (e: com.opensolr.mail.net.EndpointMissingException) {
                    return true
                }
                val boxes = db.mailboxes(account.key).associateBy { it.id }
                indexFull(Jmap(context, account), solr, name, account, fresh.keys.toList(), boxes, notesBox(boxes.values), fresh)
            }
        }
        return false
    }

    /** Downloads the readable attachments of several messages (one Email/get for all of them) and returns their text per message. */
    private suspend fun readAttachments(account: MailAccount, index: String, emailIds: List<String>): Map<String, String> {
        val jmap = Jmap(context, account)
        val r = jmap.call("Email/get", JSONObject().put("ids", JSONArray(emailIds)).put("properties", JSONArray(listOf("id", "attachments"))))
        val list = r.getJSONArray("list")
        val perMessage = (0 until list.length()).associate { i ->
            val o = list.getJSONObject(i)
            o.getString("id") to MailSync.parseBody(o.put("bodyValues", JSONObject())).attachments.filter { readable(it) }.take(10)
        }
        val dir = java.io.File(context.cacheDir, "att-read").apply { mkdirs() }
        val texts = HashMap<String, StringBuilder>()
        emailIds.forEach { texts[it] = StringBuilder() }
        val all = perMessage.flatMap { (id, atts) -> atts.map { id to it } }
        suspend fun download(a: com.opensolr.mail.data.Attachment): java.io.File? {
            val f = java.io.File(dir, a.blobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(120))
            return runCatching { jmap.download(a.blobId, a.name, a.type, f) }.map { f.takeIf { it.length() in 1..MAX_ATTACHMENT_BYTES } }
                .getOrNull().also { if (it == null) f.delete() }
        }
        val images = all.filter { isImage(it.second) }
        val documents = all - images.toSet()
        // A picture never leaves the phone as it is: only a 1024 px JPEG copy is read, for what it
        // shows (caption and labels) and for the text printed in it, 10 to a call.
        images.chunked(10).forEach { chunk ->
            // The ten downloads of a chunk go at once: each one is a wait on Fastmail, not work for the phone.
            val copies = coroutineScope { chunk.map { (_, a) -> async(Dispatchers.IO) { download(a)?.let { f -> shrink(f).also { f.delete() } } } }.awaitAll() }
            val sendable = chunk.indices.filter { copies[it] != null }
            if (sendable.isEmpty()) return@forEach
            val read = runCatching { api.imageToText(index, sendable.map { copies[it]!! }) }.getOrDefault(emptyList())
            sendable.forEachIndexed { j, i -> val (id, a) = chunk[i]; read.getOrNull(j)?.let { texts[id]?.append(a.name)?.append(":\n")?.append(it)?.append("\n\n") } }
        }
        // Documents go at most 5 and 25 MB to a call.
        val batches = ArrayList<MutableList<Pair<String, com.opensolr.mail.data.Attachment>>>()
        var used = 0L
        documents.forEach { d ->
            if (batches.isEmpty() || batches.last().size >= 5 || used + d.second.size > 25L * 1024 * 1024) { batches.add(ArrayList()); used = 0 }
            batches.last() += d
            used += d.second.size
        }
        batches.forEach { chunk ->
            val files = coroutineScope { chunk.map { (_, a) -> async(Dispatchers.IO) { download(a) } }.awaitAll() }
            val sendable = chunk.indices.filter { files[it] != null }
            if (sendable.isNotEmpty()) {
                fun payload(i: Int) = chunk[i].second.name.ifBlank { "document" } to files[i]!!.readBytes()
                // One document the server cannot read must not stop the others, nor hold the whole indexing
                // back: when the batch fails each one is tried alone, and one that fails alone is skipped.
                // A server error is tried once more after a pause, then each document alone; one that still
                // fails is skipped. No network still stops the run, which tries again.
                val read: List<String?> = try {
                    api.docToText(index, sendable.map { payload(it) })
                } catch (e: com.opensolr.mail.net.ServiceException) {
                    kotlinx.coroutines.delay(3_000L)
                    try {
                        api.docToText(index, sendable.map { payload(it) })
                    } catch (e2: com.opensolr.mail.net.ServiceException) {
                        sendable.map { i -> try { api.docToText(index, listOf(payload(i))).firstOrNull() } catch (x: com.opensolr.mail.net.ServiceException) { null } }
                    }
                }
                sendable.forEachIndexed { j, i -> val (id, a) = chunk[i]; read.getOrNull(j)?.let { texts[id]?.append(a.name)?.append(":\n")?.append(it)?.append("\n\n") } }
            }
            files.forEach { it?.delete() }
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
        val error: String? = null,
        val at: Long = 0,
    ) {
        /** Messages at Fastmail not yet in the index. */
        val messagesLeft: Long get() = if (mailTotal < 0 || upToDate < 0) -1 else (mailTotal - upToDate).coerceAtLeast(0)

        /** Messages whose attachments are still to be read: those in the index plus those with attachments not indexed yet. */
        val attLeft: Long get() = if (attachmentsLeft < 0) -1 else attachmentsLeft + if (mailWithAtt < 0 || indexedWithAtt < 0) 0 else (mailWithAtt - indexedWithAtt).coerceAtLeast(0)

        /** Done and total work over both, for one honest progress bar; null until the totals are known. */
        val progress: Pair<Long, Long>? get() {
            if (messagesLeft < 0 || attLeft < 0 || mailWithAtt < 0) return null
            val total = mailTotal + mailWithAtt
            val left = messagesLeft + attLeft.coerceAtMost(mailWithAtt)
            return if (total <= 0) null else (total - left).coerceAtLeast(0) to total
        }
    }

    companion object {
        const val VECTOR = "embeddings_vec"
        const val DOC_VERSION = 7
        /** The most one text may weigh at batch_embed; a longer one is cut to it. */
        private const val MAX_EMBED_BYTES = 40_000

        /** [text] at its first [MAX_EMBED_BYTES] bytes, cut between characters, never inside one. */
        fun fitToEmbed(text: String): String {
            val bytes = text.toByteArray()
            if (bytes.size <= MAX_EMBED_BYTES) return text
            var end = MAX_EMBED_BYTES
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return String(bytes, 0, end, Charsets.UTF_8)
        }
        private const val BATCH = 40

        /** Without vectors a batch is only a read, so it carries more messages and less of each body. */
        private const val WORDS_BATCH = 100
        private const val FULL_BODY_BYTES = 120_000
        private const val WORDS_BODY_BYTES = 60_000

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

        private const val MAX_BODY = 30_000
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
        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        /**
         * The account as the shared index knows it: derived from the Fastmail address, so every phone
         * of the same Opensolr account writes one copy of each message under the same id.
         */
        fun indexKey(account: MailAccount): String = indexKeyOf(account.username)

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

        /** What the attachment pass can read: pictures (OCR) and documents (text), never archives. */
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
        private const val OCR_EDGE_PX = 1024
        private const val MAX_SOURCE_PIXELS = 200_000_000L
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")
        private val DOC_EXT = setOf("pdf", "doc", "docx", "rtf", "odt", "txt", "html", "htm")
        private val DOC_TYPES = setOf(
            "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf", "text/rtf", "application/vnd.oasis.opendocument.text", "text/plain", "text/html",
        )

        /**
         * A 1024 px JPEG of a picture file, turned upright from its EXIF, decoded at the smallest
         * sample size that still covers 1024 px so a huge picture never fills the memory. Null for
         * anything the phone cannot decode or that claims more pixels than a real photo has.
         */
        fun shrink(file: java.io.File): ByteArray? = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) return null
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= OCR_EDGE_PX) sample *= 2
            val decoded = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rotation = runCatching { androidx.exifinterface.media.ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
            val longEdge = maxOf(decoded.width, decoded.height)
            val scale = if (longEdge > OCR_EDGE_PX) OCR_EDGE_PX.toFloat() / longEdge else 1f
            val matrix = android.graphics.Matrix().apply {
                if (scale != 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            val upright = if (matrix.isIdentity) decoded else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            val out = java.io.ByteArrayOutputStream()
            upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            out.toByteArray()
        }.getOrNull()

        /** What the vector is made of: the subject and the whole body, both plain text; empty when the message has neither. */
        fun embedTextOf(subject: String, body: String): String {
            val sub = Html.plain(subject)
            val text = Html.plain(body)
            return when {
                sub.isEmpty() -> text
                text.isEmpty() -> sub
                else -> sub + "\n\n" + text
            }
        }
    }
}
