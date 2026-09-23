package com.opensolr.mail.jmap

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.Address
import com.opensolr.mail.data.Attachment
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Mailbox
import com.opensolr.mail.data.Message
import com.opensolr.mail.data.Role
import com.opensolr.mail.data.View
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** Brings one account's mailboxes and messages in step with Fastmail, and feeds the index queue from the same change stream. */
class MailSync(private val context: Context) {

    private val db = MailDb.get(context)
    private val store = AccountStore.get(context)

    /** Messages that arrived in an inbox during this sync, unread: what the notifier looks at. */
    data class Outcome(val arrived: List<Message>)

    /**
     * One round trip per sync in the common case: mailboxes, identities when due, the email changes
     * and the headers of every created and updated message, chained with JMAP result references.
     */
    suspend fun sync(account: MailAccount): Outcome = lockOf(account.key).withLock {
        val jmap = Jmap(context, account)
        val acc = account.key
        val state = db.state(acc, STATE_EMAIL)
        val identitiesDue = db.state(acc, "identity_at")?.toLongOrNull()?.let { System.currentTimeMillis() - it > 86_400_000 } != false
        if (state == null) {
            syncMailboxes(jmap)
            runCatching { syncIdentities(jmap) }
            initialWindow(jmap)
            return@withLock Outcome(emptyList())
        }
        var since: String = state
        var first = true
        val arrived = ArrayList<Message>()
        while (true) {
            val b = Jmap.Batch(if (first && identitiesDue) Jmap.SEND else Jmap.MAIL)
            val boxesId = if (first) b.add("Mailbox/get", JSONObject().put("accountId", jmap.accountId).put("ids", JSONObject.NULL).put("properties", MAILBOX_PROPS)) else null
            val identId = if (first && identitiesDue) b.add("Identity/get", JSONObject().put("accountId", jmap.accountId).put("ids", JSONObject.NULL)) else null
            val chId = b.add("Email/changes", JSONObject().put("accountId", jmap.accountId).put("sinceState", since).put("maxChanges", 500))
            val crId = b.add("Email/get", JSONObject().put("accountId", jmap.accountId).put("properties", Jmap.HEADER_PROPS)
                .put("#ids", JSONObject().put("resultOf", chId).put("name", "Email/changes").put("path", "/created")))
            val upId = b.add("Email/get", JSONObject().put("accountId", jmap.accountId).put("properties", Jmap.HEADER_PROPS)
                .put("#ids", JSONObject().put("resultOf", chId).put("name", "Email/changes").put("path", "/updated")))
            val res = jmap.send(b)
            boxesId?.let { storeMailboxes(acc, res.get(it)) }
            identId?.let { id -> res.opt(id)?.let { storeIdentities(acc, it) } }
            val r = try {
                res.get(chId)
            } catch (e: Jmap.JmapError) {
                if (e.type == "cannotCalculateChanges") {
                    db.setState(acc, STATE_EMAIL, null)
                    initialWindow(jmap)
                    return@withLock Outcome(emptyList())
                }
                throw e
            }
            val created = r.getJSONArray("created").strings()
            val updated = r.getJSONArray("updated").strings()
            val destroyed = r.getJSONArray("destroyed").strings()
            if (destroyed.isNotEmpty()) {
                db.deleteMessages(acc, destroyed)
                db.queueIndex(acc, destroyed, OP_DELETE)
            }
            val fetched = ArrayList<Message>()
            listOf(crId, upId).forEach { id ->
                val list = res.get(id).getJSONArray("list")
                for (i in 0 until list.length()) fetched += parseHeader(acc, list.getJSONObject(i))
            }
            db.upsertMessages(fetched)
            val createdSet = created.toHashSet()
            db.queueIndex(acc, created, OP_UPSERT)
            db.queueIndex(acc, updated.filterNot { it in createdSet }, OP_META)

            val inboxIds = db.mailboxes(acc).filter { it.role == Role.INBOX.jmap }.map { it.id }.toHashSet()
            val recent = System.currentTimeMillis() - 6 * 3_600_000L
            arrived += fetched.filter { it.id in createdSet && !it.seen && !it.draft && it.received > recent && it.mailboxIds.any { bx -> bx in inboxIds } }

            since = r.getString("newState")
            db.setState(acc, STATE_EMAIL, since)
            first = false
            if (!r.optBoolean("hasMoreChanges")) break
        }
        Outcome(arrived)
    }

    suspend fun syncAll(): List<Message> {
        val arrived = ArrayList<Message>()
        store.all().forEach { a -> runCatching { arrived += sync(a).arrived } }
        return arrived
    }

    private suspend fun syncMailboxes(jmap: Jmap) {
        storeMailboxes(jmap.account.key, jmap.call("Mailbox/get", JSONObject().put("ids", JSONObject.NULL).put("properties", MAILBOX_PROPS)))
    }

    private fun storeMailboxes(acc: String, r: JSONObject) {
        val list = r.getJSONArray("list")
        val boxes = (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            Mailbox(
                acc = acc,
                id = o.getString("id"),
                name = o.optString("name"),
                parentId = o.optString("parentId").takeIf { it.isNotEmpty() && it != "null" },
                role = o.optString("role").takeIf { it.isNotEmpty() && it != "null" },
                sortOrder = o.optInt("sortOrder"),
                total = o.optInt("totalEmails"),
                unread = o.optInt("unreadEmails"),
            )
        }
        db.replaceMailboxes(acc, boxes)
    }

    private suspend fun syncIdentities(jmap: Jmap) {
        storeIdentities(jmap.account.key, jmap.call("Identity/get", JSONObject().put("ids", JSONObject.NULL), Jmap.SEND))
    }

    private fun storeIdentities(acc: String, r: JSONObject) {
        val list = r.getJSONArray("list")
        db.replaceIdentities(acc, (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            MailDb.Identity(
                acc = acc,
                id = o.getString("id"),
                name = o.optString("name"),
                email = o.optString("email"),
                replyTo = Address.listToJson(Address.listFrom(o.optJSONArray("replyTo"))),
                bcc = Address.listToJson(Address.listFrom(o.optJSONArray("bcc"))),
                signature = o.optString("textSignature"),
            )
        })
        db.setState(acc, "identity_at", System.currentTimeMillis().toString())
    }

    /** First sync of an account: the newest messages, plus the newest of each special mailbox, so every unified view starts full. */
    private suspend fun initialWindow(jmap: Jmap) {
        val acc = jmap.account.key
        val b = Jmap.Batch()
        val stateId = b.add("Email/get", JSONObject().put("accountId", jmap.accountId).put("ids", JSONArray()))
        val q = b.add(
            "Email/query",
            JSONObject().put("accountId", jmap.accountId)
                .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false)))
                .put("limit", INITIAL_WINDOW),
        )
        val perBox = db.mailboxes(acc).filter { it.role != null }.map { box ->
            b.add(
                "Email/query",
                JSONObject().put("accountId", jmap.accountId).put("filter", JSONObject().put("inMailbox", box.id))
                    .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false)))
                    .put("limit", PER_BOX_WINDOW),
            )
        }
        val res = jmap.send(b)
        val state = res.get(stateId).getString("state")
        val ids = res.get(q).getJSONArray("ids").strings().toMutableList()
        perBox.forEach { id -> res.opt(id)?.getJSONArray("ids")?.strings()?.let { ids += it } }
        db.upsertMessages(getHeaders(jmap, ids.distinct()))
        db.setState(acc, STATE_EMAIL, state)
        if (db.state(acc, STATE_BACKFILL) == null) db.setState(acc, STATE_BACKFILL, "start")
    }

    private suspend fun queryIds(jmap: Jmap, filter: JSONObject, limit: Int): List<String> {
        val r = jmap.call(
            "Email/query",
            JSONObject().put("filter", filter)
                .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false)))
                .put("limit", limit),
        )
        return r.getJSONArray("ids").strings()
    }

    /** Headers for [ids], 250 per call. */
    suspend fun getHeaders(jmap: Jmap, ids: List<String>): List<Message> {
        val out = ArrayList<Message>(ids.size)
        ids.chunked(250).forEach { chunk ->
            val r = jmap.call("Email/get", JSONObject().put("ids", JSONArray(chunk)).put("properties", Jmap.HEADER_PROPS))
            val list = r.getJSONArray("list")
            for (i in 0 until list.length()) out += parseHeader(jmap.account.key, list.getJSONObject(i))
        }
        return out
    }

    /** Older messages of [view] than the oldest held locally: 100 per account per call. Returns how many arrived. */
    suspend fun loadOlder(view: View): Int {
        var total = 0
        val accounts = when (view) {
            is View.Box -> listOfNotNull(store.get(view.acc))
            else -> store.all()
        }
        accounts.forEach { a ->
            val jmap = Jmap(context, a)
            val boxes = when (view) {
                is View.Unified -> db.mailboxes(a.key).filter { it.role == view.role.jmap }.map { it.id }
                is View.Box -> listOf(view.mailboxId)
                View.Flagged -> emptyList()
            }
            val filter = if (view == View.Flagged) JSONObject().put("hasKeyword", "\$flagged") else if (boxes.size == 1) JSONObject().put("inMailbox", boxes[0]) else return@forEach
            val oldest = if (view == View.Flagged) null else db.oldestIn(a.key, boxes)
            oldest?.let { filter.put("before", iso(it)) }
            // Query and headers in one round trip, chained by a result reference.
            val msgs = runCatching {
                val b = Jmap.Batch()
                val q = b.add("Email/query", JSONObject().put("accountId", jmap.accountId).put("filter", filter)
                    .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false))).put("limit", 100))
                val g = b.add("Email/get", JSONObject().put("accountId", jmap.accountId).put("properties", Jmap.HEADER_PROPS)
                    .put("#ids", JSONObject().put("resultOf", q).put("name", "Email/query").put("path", "/ids")))
                val list = jmap.send(b).get(g).getJSONArray("list")
                (0 until list.length()).map { parseHeader(a.key, list.getJSONObject(it)) }
            }.getOrDefault(emptyList())
            db.upsertMessages(msgs)
            total += msgs.size
        }
        return total
    }

    /** Every message of a conversation, fetched when some are not held locally yet. */
    suspend fun fetchThread(account: MailAccount, threadId: String) {
        val jmap = Jmap(context, account)
        val r = jmap.call("Thread/get", JSONObject().put("ids", JSONArray().put(threadId)))
        val ids = r.getJSONArray("list").optJSONObject(0)?.optJSONArray("emailIds")?.strings().orEmpty()
        db.upsertMessages(getHeaders(jmap, ids))
    }

    /** The body and attachments of one message, cached locally. */
    suspend fun fetchBody(account: MailAccount, id: String) = fetchBodies(account, listOf(id))

    /** Bodies of several messages of one account in one request. */
    suspend fun fetchBodies(account: MailAccount, ids: List<String>) {
        if (ids.isEmpty()) return
        val jmap = Jmap(context, account)
        val r = jmap.call(
            "Email/get",
            JSONObject().put("ids", JSONArray(ids))
                .put("properties", JSONArray(listOf("id", "textBody", "htmlBody", "attachments", "bodyValues")))
                .put("fetchTextBodyValues", true)
                .put("fetchHTMLBodyValues", true)
                .put("maxBodyValueBytes", 4_000_000),
        )
        val list = r.getJSONArray("list")
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val body = parseBody(o)
            db.setBody(account.key, o.getString("id"), body.text, body.html, body.attachments)
        }
    }

    data class Body(val text: String, val html: String, val attachments: List<Attachment>)

    companion object {
        const val STATE_EMAIL = "email"
        const val STATE_BACKFILL = "backfill"
        const val OP_UPSERT = "u"
        const val OP_META = "m"
        const val OP_DELETE = "d"
        private const val INITIAL_WINDOW = 500
        private val MAILBOX_PROPS = JSONArray(listOf("id", "name", "parentId", "role", "sortOrder", "totalEmails", "unreadEmails"))
        private const val PER_BOX_WINDOW = 100

        private val locks = ConcurrentHashMap<String, Mutex>()
        fun lockOf(key: String): Mutex = locks.getOrPut(key) { Mutex() }

        fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

        private val isoIn = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        fun parseDate(s: String?): Long {
            if (s.isNullOrEmpty() || s == "null") return 0L
            return runCatching { isoIn.get()!!.parse(s.take(19))!!.time }.getOrDefault(0L)
        }

        fun iso(ms: Long): String = isoIn.get()!!.format(ms) + "Z"

        fun parseHeader(acc: String, o: JSONObject): Message {
            val kw = o.optJSONObject("keywords") ?: JSONObject()
            val boxes = o.optJSONObject("mailboxIds")?.keys()?.asSequence()?.toSet().orEmpty()
            return Message(
                acc = acc,
                id = o.getString("id"),
                threadId = o.optString("threadId"),
                mailboxIds = boxes,
                seen = kw.optBoolean("\$seen"),
                flagged = kw.optBoolean("\$flagged"),
                draft = kw.optBoolean("\$draft"),
                answered = kw.optBoolean("\$answered"),
                received = parseDate(o.optString("receivedAt")),
                subject = Html.plain(o.optString("subject").takeIf { it != "null" }.orEmpty()),
                from = Address.listFrom(o.optJSONArray("from")),
                to = Address.listFrom(o.optJSONArray("to")),
                cc = Address.listFrom(o.optJSONArray("cc")),
                bcc = Address.listFrom(o.optJSONArray("bcc")),
                replyTo = Address.listFrom(o.optJSONArray("replyTo")),
                preview = o.optString("preview").takeIf { it != "null" }.orEmpty(),
                hasAttachment = o.optBoolean("hasAttachment"),
                size = o.optLong("size"),
                messageId = o.optJSONArray("messageId")?.optString(0).orEmpty(),
                inReplyTo = o.optJSONArray("inReplyTo")?.optString(0).orEmpty(),
                references = o.optJSONArray("references")?.let { a -> (0 until a.length()).joinToString(" ") { "<${a.getString(it)}>" } }.orEmpty(),
                bodyText = null,
                bodyHtml = null,
                attachments = emptyList(),
            )
        }

        fun parseBody(o: JSONObject): Body {
            val values = o.optJSONObject("bodyValues") ?: JSONObject()
            fun partsText(parts: JSONArray?, want: String): List<Pair<String, String>> {
                if (parts == null) return emptyList()
                return (0 until parts.length()).mapNotNull { i ->
                    val p = parts.getJSONObject(i)
                    val v = values.optJSONObject(p.optString("partId"))?.optString("value") ?: return@mapNotNull null
                    p.optString("type") to v
                }.filter { want.isEmpty() || it.first == want }
            }
            val text = partsText(o.optJSONArray("textBody"), "").joinToString("\n\n") { (type, v) ->
                Html.plain(if (type == "text/html") Html.toText(v) else v)
            }.trim()
            val html = partsText(o.optJSONArray("htmlBody"), "").joinToString("\n") { (type, v) ->
                if (type == "text/html") v else Html.fromText(v)
            }
            val atts = o.optJSONArray("attachments")?.let { a ->
                (0 until a.length()).map { i ->
                    val p = a.getJSONObject(i)
                    Attachment(
                        blobId = p.optString("blobId"),
                        name = p.optString("name").takeIf { it != "null" }.orEmpty(),
                        type = p.optString("type"),
                        size = p.optLong("size"),
                        cid = p.optString("cid").takeIf { it.isNotEmpty() && it != "null" },
                        inline = p.optString("disposition") == "inline",
                    )
                }
            }.orEmpty()
            return Body(text, html, atts)
        }
    }
}
