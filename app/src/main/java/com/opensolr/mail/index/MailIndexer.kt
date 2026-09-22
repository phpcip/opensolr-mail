package com.opensolr.mail.index

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Mailbox
import com.opensolr.mail.data.Message
import com.opensolr.mail.jmap.Jmap
import com.opensolr.mail.jmap.MailSync
import com.opensolr.mail.jmap.MailSync.Companion.strings
import com.opensolr.mail.net.IndexLimitException
import com.opensolr.mail.net.IndexMissingException
import com.opensolr.mail.net.OpensolrApi
import com.opensolr.mail.net.QuotaExceededException
import com.opensolr.mail.net.SignInRequiredException
import com.opensolr.mail.net.VectorNotAllowedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    suspend fun run(deadline: Long): Outcome = runLock.withLock {
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
                store.all().forEach { db.setState(it.key, MailSync.STATE_BACKFILL, "start") }
                sp.edit().putInt("doc_version", DOC_VERSION).putBoolean("reindex_all", false).commit()
            }

            var more = false
            for (account in store.all()) {
                if (System.currentTimeMillis() > deadline) { more = true; break }
                if (!indexAccount(account, solr, name, deadline)) more = true
            }
            if (!more && System.currentTimeMillis() < deadline) more = !vectorBackfill(solr, name, deadline)
            if (!more && System.currentTimeMillis() < deadline && unmetered()) more = !attachmentPass(solr, name, deadline)
            _status.value = _status.value.copy(error = null)
            if (more) Outcome.MORE else Outcome.DONE
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

    /** Documents in the index, how many carry a vector, what is still queued, and whether every account's history was walked. One faceted query. */
    private suspend fun refreshCounts(solr: SolrClient?) {
        val s = _status.value
        var indexed = s.indexed
        var meaning = s.withMeaning
        var attachments = s.attachmentsLeft
        if (solr != null) runCatching {
            val r = solr.select(listOf("q" to "*:*", "rows" to "0", "facet" to "true", "facet.query" to "{!key=v}vec_b:true", "facet.query" to "{!key=a}att_todo_b:true"))
            indexed = r.getJSONObject("response").getLong("numFound")
            val fq = r.optJSONObject("facet_counts")?.optJSONObject("facet_queries")
            meaning = fq?.optLong("v") ?: meaning
            attachments = fq?.optLong("a") ?: attachments
        }
        val done = store.all().isNotEmpty() && store.all().all { db.state(it.key, MailSync.STATE_BACKFILL) == "done" }
        _status.value = _status.value.copy(indexed = indexed, withMeaning = meaning, attachmentsLeft = attachments, pending = db.indexPending(), historyDone = done)
    }

    private fun persist() {
        val s = _status.value
        context.getSharedPreferences("index_status", Context.MODE_PRIVATE).edit()
            .putLong("indexed", s.indexed).putLong("meaning", s.withMeaning).putInt("pending", s.pending).putLong("attachments", s.attachmentsLeft)
            .putBoolean("done", s.historyDone).putBoolean("no_room", s.noRoom).putString("error", s.error).putLong("at", s.at).apply()
    }

    /** The last known status, read once per process so the screens have numbers before the first run. */
    fun restore() {
        if (_status.value.at != 0L) return
        val sp = context.getSharedPreferences("index_status", Context.MODE_PRIVATE)
        _status.value = Status(
            indexed = sp.getLong("indexed", -1), withMeaning = sp.getLong("meaning", -1), pending = sp.getInt("pending", 0), attachmentsLeft = sp.getLong("attachments", -1),
            historyDone = sp.getBoolean("done", false), noRoom = sp.getBoolean("no_room", false), error = sp.getString("error", null), at = sp.getLong("at", 0),
        )
    }

    /** True when the account has nothing left to do. */
    private suspend fun indexAccount(account: MailAccount, solr: SolrClient, name: String, deadline: Long): Boolean {
        val acc = account.key
        val jmap = Jmap(context, account)
        val boxes = db.mailboxes(acc).associateBy { it.id }
        val notes = notesBox(boxes.values)

        while (System.currentTimeMillis() < deadline) {
            val deletes = db.indexBatch(acc, MailSync.OP_DELETE, 500)
            if (deletes.isNotEmpty()) {
                solr.deleteIds(deletes.map { docId(acc, it) })
                db.indexDone(acc, deletes)
                continue
            }
            val meta = db.indexBatch(acc, MailSync.OP_META, 200)
            if (meta.isNotEmpty()) {
                applyMeta(acc, meta)
                continue
            }
            val ups = db.indexBatch(acc, MailSync.OP_UPSERT, BATCH)
            if (ups.isNotEmpty()) {
                if (_status.value.phase != Phase.MAIL) _status.value = _status.value.copy(phase = Phase.MAIL, pending = db.indexPending())
                indexFull(jmap, solr, name, account, ups, boxes, notes)
                val s = _status.value
                _status.value = s.copy(pending = db.indexPending(), indexed = if (s.indexed >= 0) s.indexed + ups.size else s.indexed)
                continue
            }
            if (_status.value.phase != Phase.HISTORY) _status.value = _status.value.copy(phase = Phase.HISTORY)
            if (!backfillPage(jmap, acc, notes)) return true
        }
        return false
    }

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
        val acc = account.key
        // What the attachment pass already read survives a rewrite of the document.
        val kept = HashMap<String, String>()
        val readBefore = HashSet<String>()
        runCatching {
            val r0 = solr.select(listOf(
                "q" to "*:*", "fq" to "{!terms f=id separator=| v=\$ids}", "ids" to ids.joinToString("|") { docId(acc, it) },
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
        val attText = kept + fresh
        val attDone = readBefore + fresh.keys
        val r = jmap.call(
            "Email/get",
            JSONObject().put("ids", JSONArray(ids))
                .put("properties", JSONArray(Jmap.HEADER_PROPS.strings() + listOf("textBody", "htmlBody", "attachments", "bodyValues")))
                .put("fetchTextBodyValues", true)
                .put("fetchHTMLBodyValues", true)
                .put("maxBodyValueBytes", 120_000),
        )
        val list = r.getJSONArray("list")
        val found = HashSet<String>()
        val entries = ArrayList<Pair<Message, MailSync.Body>>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val m = MailSync.parseHeader(acc, o)
            found += m.id
            if (notes != null && m.mailboxIds.all { it == notes.id }) continue
            entries += m to MailSync.parseBody(o)
        }
        val gone = ids.filterNot { it in found }
        if (gone.isNotEmpty()) solr.deleteIds(gone.map { docId(acc, it) })

        val vectors = embed(name, entries.map { (m, b) ->
            embedTextOf(
                m.subject, m.sender?.let { (it.name + " " + it.email).trim() }.orEmpty(), m.to.joinToString(", ") { it.label }, b.text,
                b.attachments.filter { !it.inline }.map { it.name }.filter { it.isNotBlank() }, attText[m.id].orEmpty(),
            )
        })
        val docs = JSONArray()
        entries.forEachIndexed { i, (m, b) ->
            val o = doc(account, m, b, boxes, vectors?.getOrNull(i))
            attText[m.id]?.let { o.put("attachment_text_t", it.take(MAX_ATTACHMENT_TEXT)) }
            if (m.id in attDone) o.put("att_todo_b", false)
            docs.put(o)
        }
        if (docs.length() > 0) solr.add(docs)
        db.indexDone(acc, ids)
    }

    /** Vectors for [texts], or null when the plan or the monthly quota says no; the documents then go in with words only. */
    private suspend fun embed(name: String, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty() || !prefs.vectorAllowed || prefs.embedPausedUntil > System.currentTimeMillis() || embedDownUntil > System.currentTimeMillis()) return null
        return try {
            api.batchEmbed(name, texts)
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
    private suspend fun vectorBackfill(solr: SolrClient, name: String, deadline: Long): Boolean {
        if (!prefs.vectorAllowed || prefs.embedPausedUntil > System.currentTimeMillis() || embedDownUntil > System.currentTimeMillis()) return true
        val res = solr.select(listOf("q" to "*:*", "fq" to "vec_b:false", "fl" to "account_s,email_id_s", "rows" to "200", "sort" to "received_dt desc"))
        val docs = res.optJSONObject("response")?.optJSONArray("docs") ?: return true
        if (docs.length() == 0) return true
        (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }.forEach { (acc, list) ->
            if (store.get(acc) != null) db.queueIndex(acc, list.map { it.optString("email_id_s") }, MailSync.OP_UPSERT)
        }
        return false
    }

    private fun doc(account: MailAccount, m: Message, body: MailSync.Body, boxes: Map<String, Mailbox>, vector: FloatArray?): JSONObject {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = m.received }
        val text = body.text.take(MAX_BODY)
        val o = JSONObject()
            .put("id", docId(account.key, m.id))
            .put("account_s", account.key)
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
    private suspend fun attachmentPass(solr: SolrClient, name: String, deadline: Long): Boolean {
        while (System.currentTimeMillis() < deadline) {
            val res = solr.select(listOf("q" to "*:*", "fq" to "att_todo_b:true", "rows" to "5", "sort" to "received_dt desc", "fl" to "account_s,email_id_s"))
            val docs = res.optJSONObject("response")?.optJSONArray("docs") ?: return true
            val left = res.optJSONObject("response")?.optLong("numFound") ?: 0L
            _status.value = _status.value.copy(phase = Phase.ATTACHMENTS, attachmentsLeft = left)
            if (docs.length() == 0) return true
            val byAcc = (0 until docs.length()).map { docs.getJSONObject(it) }.groupBy { it.optString("account_s") }
            for ((acc, list) in byAcc) {
                val account = store.get(acc) ?: continue
                val ids = list.map { it.optString("email_id_s") }
                val fresh = try {
                    readAttachments(account, name, ids)
                } catch (e: com.opensolr.mail.net.EndpointMissingException) {
                    return true
                }
                val boxes = db.mailboxes(acc).associateBy { it.id }
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
        suspend fun bytes(a: com.opensolr.mail.data.Attachment): ByteArray {
            val f = java.io.File(dir, a.blobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
            jmap.download(a.blobId, a.name, a.type, f)
            return f.readBytes().also { f.delete() }
        }
        val images = all.filter { it.second.type.lowercase().startsWith("image/") }
        val documents = all - images.toSet()
        images.chunked(5).forEach { chunk ->
            val read = runCatching { api.imageOcr(index, chunk.map { bytes(it.second) }) }.getOrDefault(emptyList())
            chunk.forEachIndexed { i, (id, a) -> read.getOrNull(i)?.let { texts[id]?.append(a.name)?.append(":\n")?.append(it)?.append("\n\n") } }
        }
        documents.chunked(5).forEach { chunk ->
            val read = api.docToText(index, chunk.map { (_, a) -> a.name.ifBlank { "document" } to bytes(a) })
            chunk.forEachIndexed { i, (id, a) -> read.getOrNull(i)?.let { texts[id]?.append(a.name)?.append(":\n")?.append(it)?.append("\n\n") } }
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
        /** Messages whose attachments are still to be read into text. */
        val attachmentsLeft: Long = -1,
        /** What the running indexer is busy with right now. */
        val phase: Phase = Phase.IDLE,
        val historyDone: Boolean = false,
        val noRoom: Boolean = false,
        val error: String? = null,
        val at: Long = 0,
    )

    companion object {
        const val VECTOR = "embeddings_vec"
        const val DOC_VERSION = 3
        private const val BATCH = 40
        private const val MAX_BODY = 30_000
        private const val MAX_ATTACHMENT_TEXT = 100_000
        private const val EMBED_CHARS = 1800

        private val runLock = Mutex()
        private const val EMBED_RETRY_MS = 10 * 60_000L
        @Volatile private var embedDownUntil = 0L
        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status

        fun docId(acc: String, emailId: String) = "$acc:$emailId"

        fun embedText(m: Message, body: String, attachments: List<String> = emptyList()): String =
            embedTextOf(m.subject, m.sender?.let { (it.name + " " + it.email).trim() }.orEmpty(), m.to.joinToString(", ") { it.label }, body, attachments)

        /** "Name (address)", or the bare address: the same person on ten addresses stays ten entries. */
        fun labelOf(a: com.opensolr.mail.data.Address): String {
            val email = a.email.trim().lowercase()
            val name = a.name.trim().trim('"', '\'').takeIf { it.isNotBlank() && !it.equals(email, true) }
            return if (name != null) "$name ($email)" else email
        }

        fun domainOf(email: String): String? = email.substringAfter('@', "").lowercase().trim().takeIf { it.contains('.') }

        /** What the attachment pass can read: pictures (OCR) and documents (text), never archives. */
        fun readable(a: com.opensolr.mail.data.Attachment): Boolean {
            val type = a.type.lowercase()
            val ext = a.name.substringAfterLast('.', "").lowercase()
            if (a.size <= 0 || a.size > 10L * 1024 * 1024) return false
            if (type.startsWith("image/")) return !a.inline && (type.contains("jpeg") || type.contains("png") || ext in setOf("jpg", "jpeg", "png"))
            return ext in DOC_EXT || type in DOC_TYPES
        }

        private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "csv", "html", "htm", "rtf")
        private val DOC_TYPES = setOf(
            "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation", "text/plain", "text/csv", "text/html",
        )

        /** What the vector is made of: who, to whom, about what, and the new words of the message, without quoted history or signature. */
        fun embedTextOf(subject: String, from: String, to: String, body: String, attachments: List<String> = emptyList(), attachmentText: String = ""): String {
            val fresh = body.lineSequence()
                .takeWhile { line -> !line.startsWith("-- ") && !Regex("^On .{4,120} wrote:\\s*$").matches(line.trim()) && !line.startsWith("-----Original Message") }
                .filterNot { it.trimStart().startsWith(">") }
                .joinToString("\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            val head = buildString {
                if (subject.isNotBlank()) append("Subject: ").append(subject).append('\n')
                if (from.isNotBlank()) append("From: ").append(from).append('\n')
                if (to.isNotBlank()) append("To: ").append(to).append('\n')
                if (attachments.isNotEmpty()) append("Attachments: ").append(attachments.joinToString(", ")).append('\n')
            }
            val main = (head + "\n" + fresh).take(EMBED_CHARS)
            val extra = attachmentText.trim().replace(Regex("\\s+"), " ")
            val room = EMBED_CHARS + 600 - main.length
            return (if (extra.isNotEmpty() && room > 80) main + "\n\n" + extra.take(room) else main).ifBlank { subject.ifBlank { "(empty)" } }
        }
    }
}
