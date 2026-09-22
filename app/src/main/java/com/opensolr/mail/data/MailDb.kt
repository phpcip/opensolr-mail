package com.opensolr.mail.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** The local copy of the mail: mailboxes, message headers (bodies once opened), identities, sync state, and the queues. */
class MailDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "mail.db", null, 1) {

    /** Bumped on every write the lists care about, so screens re-query. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    fun touch() {
        _version.value = _version.value + 1
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mailbox (acc TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, parent TEXT, role TEXT, sort INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (acc, id))")
        db.execSQL("CREATE INDEX mailbox_role ON mailbox (role)")
        db.execSQL(
            "CREATE TABLE message (acc TEXT NOT NULL, id TEXT NOT NULL, thread TEXT NOT NULL, seen INTEGER NOT NULL, flagged INTEGER NOT NULL, " +
                "draft INTEGER NOT NULL, answered INTEGER NOT NULL, received INTEGER NOT NULL, subject TEXT NOT NULL, from_name TEXT NOT NULL, " +
                "from_addr TEXT NOT NULL, from_json TEXT NOT NULL, to_json TEXT NOT NULL, cc_json TEXT NOT NULL, bcc_json TEXT NOT NULL, " +
                "reply_to_json TEXT NOT NULL, preview TEXT NOT NULL, has_att INTEGER NOT NULL, size INTEGER NOT NULL, message_id TEXT NOT NULL, " +
                "in_reply_to TEXT NOT NULL, refs TEXT NOT NULL, body_text TEXT, body_html TEXT, atts_json TEXT, PRIMARY KEY (acc, id))"
        )
        db.execSQL("CREATE INDEX message_thread ON message (acc, thread, received)")
        db.execSQL("CREATE INDEX message_received ON message (received)")
        db.execSQL("CREATE INDEX message_flagged ON message (flagged, received)")
        db.execSQL("CREATE TABLE msg_box (acc TEXT NOT NULL, msg TEXT NOT NULL, box TEXT NOT NULL, PRIMARY KEY (acc, msg, box))")
        db.execSQL("CREATE INDEX msg_box_box ON msg_box (acc, box)")
        db.execSQL("CREATE TABLE state (acc TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (acc, kind))")
        db.execSQL("CREATE TABLE identity (acc TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, reply_to TEXT NOT NULL, bcc TEXT NOT NULL, signature TEXT NOT NULL, PRIMARY KEY (acc, id))")
        db.execSQL("CREATE TABLE ops (id INTEGER PRIMARY KEY AUTOINCREMENT, acc TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, created INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE index_queue (acc TEXT NOT NULL, msg TEXT NOT NULL, op TEXT NOT NULL, PRIMARY KEY (acc, msg))")
        db.execSQL("CREATE TABLE notified (acc TEXT NOT NULL, msg TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (acc, msg))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- state ----------

    fun state(acc: String, kind: String): String? =
        readableDatabase.rawQuery("SELECT value FROM state WHERE acc = ? AND kind = ?", arrayOf(acc, kind)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setState(acc: String, kind: String, value: String?) {
        if (value == null) writableDatabase.delete("state", "acc = ? AND kind = ?", arrayOf(acc, kind))
        else writableDatabase.insertWithOnConflict("state", null, ContentValues().apply {
            put("acc", acc); put("kind", kind); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- mailboxes ----------

    fun replaceMailboxes(acc: String, boxes: List<Mailbox>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("mailbox", "acc = ?", arrayOf(acc))
            boxes.forEach { putMailbox(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    fun upsertMailboxes(boxes: List<Mailbox>, destroyed: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            boxes.forEach { putMailbox(db, it) }
            destroyed.forEach { (acc, id) -> db.delete("mailbox", "acc = ? AND id = ?", arrayOf(acc, id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    private fun putMailbox(db: SQLiteDatabase, b: Mailbox) {
        db.insertWithOnConflict("mailbox", null, ContentValues().apply {
            put("acc", b.acc); put("id", b.id); put("name", b.name); put("parent", b.parentId); put("role", b.role)
            put("sort", b.sortOrder); put("total", b.total); put("unread", b.unread)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun mailboxes(acc: String? = null): List<Mailbox> {
        val sql = "SELECT acc, id, name, parent, role, sort, total, unread FROM mailbox" + (if (acc != null) " WHERE acc = ?" else "") + " ORDER BY acc, sort, name COLLATE NOCASE"
        return readableDatabase.rawQuery(sql, acc?.let { arrayOf(it) }).use { c ->
            generateSequence { if (c.moveToNext()) Mailbox(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6), c.getInt(7)) else null }.toList()
        }
    }

    fun mailboxByRole(acc: String, role: Role): Mailbox? = mailboxes(acc).firstOrNull { it.role == role.jmap }

    fun mailbox(acc: String, id: String): Mailbox? = mailboxes(acc).firstOrNull { it.id == id }

    /** Unread count per unified role, summed over accounts. */
    fun unreadByRole(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT role, SUM(unread) FROM mailbox WHERE role IS NOT NULL GROUP BY role", null).use { c ->
            val m = HashMap<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            m
        }

    // ---------- messages ----------

    fun upsertMessages(list: List<Message>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            list.forEach { m ->
                val v = ContentValues().apply {
                    put("acc", m.acc); put("id", m.id); put("thread", m.threadId)
                    put("seen", if (m.seen) 1 else 0); put("flagged", if (m.flagged) 1 else 0)
                    put("draft", if (m.draft) 1 else 0); put("answered", if (m.answered) 1 else 0)
                    put("received", m.received); put("subject", m.subject)
                    put("from_name", m.sender?.label.orEmpty()); put("from_addr", m.sender?.email.orEmpty())
                    put("from_json", Address.listToJson(m.from)); put("to_json", Address.listToJson(m.to))
                    put("cc_json", Address.listToJson(m.cc)); put("bcc_json", Address.listToJson(m.bcc))
                    put("reply_to_json", Address.listToJson(m.replyTo)); put("preview", m.preview)
                    put("has_att", if (m.hasAttachment) 1 else 0); put("size", m.size)
                    put("message_id", m.messageId); put("in_reply_to", m.inReplyTo); put("refs", m.references)
                }
                val updated = db.update("message", v, "acc = ? AND id = ?", arrayOf(m.acc, m.id))
                if (updated == 0) db.insert("message", null, v)
                db.delete("msg_box", "acc = ? AND msg = ?", arrayOf(m.acc, m.id))
                m.mailboxIds.forEach { box ->
                    db.insertWithOnConflict("msg_box", null, ContentValues().apply {
                        put("acc", m.acc); put("msg", m.id); put("box", box)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    fun deleteMessages(acc: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("message", "acc = ? AND id = ?", arrayOf(acc, id))
                db.delete("msg_box", "acc = ? AND msg = ?", arrayOf(acc, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    fun forgetAccount(acc: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("mailbox", "message", "msg_box", "state", "identity", "ops", "index_queue", "notified").forEach {
                db.delete(it, "acc = ?", arrayOf(acc))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    fun setBody(acc: String, id: String, text: String?, html: String?, atts: List<Attachment>) {
        writableDatabase.update("message", ContentValues().apply {
            put("body_text", text ?: ""); put("body_html", html ?: "")
            put("atts_json", JSONArray(atts.map { it.toJson() }).toString())
        }, "acc = ? AND id = ?", arrayOf(acc, id))
    }

    /** Local optimistic change of flags; the server is told through the ops queue. */
    fun setFlags(acc: String, ids: Collection<String>, seen: Boolean? = null, flagged: Boolean? = null) {
        if (ids.isEmpty()) return
        val v = ContentValues()
        seen?.let { v.put("seen", if (it) 1 else 0) }
        flagged?.let { v.put("flagged", if (it) 1 else 0) }
        if (v.size() == 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.update("message", v, "acc = ? AND id = ?", arrayOf(acc, it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    /** Local optimistic "mark all as read" of one mailbox, in one statement. */
    fun readAllLocal(acc: String, box: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE message SET seen = 1 WHERE acc = ? AND seen = 0 AND id IN (SELECT msg FROM msg_box WHERE acc = ? AND box = ?)",
                arrayOf(acc, acc, box),
            )
            db.execSQL("UPDATE mailbox SET unread = 0 WHERE acc = ? AND id = ?", arrayOf(acc, box))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    /** Local optimistic emptying of one mailbox: messages only in it are gone, the others just leave it. */
    fun emptyLocal(acc: String, box: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM message WHERE acc = ? AND id IN (SELECT msg FROM msg_box WHERE acc = ? AND box = ?) " +
                    "AND NOT EXISTS (SELECT 1 FROM msg_box o WHERE o.acc = message.acc AND o.msg = message.id AND o.box <> ?)",
                arrayOf(acc, acc, box, box),
            )
            db.execSQL("DELETE FROM msg_box WHERE acc = ? AND box = ?", arrayOf(acc, box))
            db.execSQL("UPDATE mailbox SET unread = 0, total = 0 WHERE acc = ? AND id = ?", arrayOf(acc, box))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    /** Local optimistic move. */
    fun moveLocal(acc: String, ids: Collection<String>, to: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("msg_box", "acc = ? AND msg = ?", arrayOf(acc, id))
                db.insert("msg_box", null, ContentValues().apply { put("acc", acc); put("msg", id); put("box", to) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    fun message(acc: String, id: String): Message? =
        readableDatabase.rawQuery("SELECT $MSG_COLS FROM message WHERE acc = ? AND id = ?", arrayOf(acc, id)).use { c ->
            if (c.moveToFirst()) readMessage(c) else null
        }?.let { withBoxes(listOf(it)).first() }

    fun thread(acc: String, threadId: String): List<Message> =
        withBoxes(readableDatabase.rawQuery("SELECT $MSG_COLS FROM message WHERE acc = ? AND thread = ? ORDER BY received ASC", arrayOf(acc, threadId)).use { c ->
            generateSequence { if (c.moveToNext()) readMessage(c) else null }.toList()
        })

    /** Many messages of one account, in chunks, with their mailboxes. */
    fun messages(acc: String, ids: List<String>): List<Message> {
        val out = ArrayList<Message>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT $MSG_COLS FROM message WHERE acc = ? AND id IN ($marks)", arrayOf(acc) + chunk).use { c ->
                while (c.moveToNext()) out += readMessage(c)
            }
        }
        return withBoxes(out)
    }

    /** Fills in the mailboxes of [list] with one query per account. */
    private fun withBoxes(list: List<Message>): List<Message> {
        if (list.isEmpty()) return list
        val boxes = list.groupBy { it.acc }.flatMap { (acc, ms) -> mailboxIdsOf(acc, ms.map { it.id }).map { (acc to it.key) to it.value } }.toMap()
        return list.map { it.copy(mailboxIds = boxes[it.acc to it.id].orEmpty()) }
    }

    fun mailboxIdsOf(acc: String, ids: List<String>): Map<String, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableSet<String>>()
        ids.chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT msg, box FROM msg_box WHERE acc = ? AND msg IN ($marks)", arrayOf(acc) + chunk).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashSet() }.add(c.getString(1))
            }
        }
        return out
    }

    private fun readMessage(c: Cursor): Message {
        val acc = c.getString(0)
        val id = c.getString(1)
        return Message(
            acc = acc, id = id, threadId = c.getString(2),
            mailboxIds = emptySet(),
            seen = c.getInt(3) == 1, flagged = c.getInt(4) == 1, draft = c.getInt(5) == 1, answered = c.getInt(6) == 1,
            received = c.getLong(7), subject = c.getString(8),
            from = Address.listFromJson(c.getString(9)), to = Address.listFromJson(c.getString(10)),
            cc = Address.listFromJson(c.getString(11)), bcc = Address.listFromJson(c.getString(12)),
            replyTo = Address.listFromJson(c.getString(13)), preview = c.getString(14),
            hasAttachment = c.getInt(15) == 1, size = c.getLong(16), messageId = c.getString(17),
            inReplyTo = c.getString(18), references = c.getString(19),
            bodyText = c.getString(20), bodyHtml = c.getString(21),
            attachments = Attachment.listFromJson(c.getString(22)),
        )
    }

    /**
     * The conversations of [view], newest first. Each row stands for the latest message of the thread
     * inside the view; [limit] rows from [offset]. One grouped query, with the per-thread details
     * read back for the page only.
     */
    fun threads(view: View, accounts: Set<String>, limit: Int, offset: Int): List<ThreadRow> {
        if (accounts.isEmpty()) return emptyList()
        val accMarks = accounts.joinToString(",") { "?" }
        val args = ArrayList<String>()
        val scope = when (view) {
            is View.Unified -> {
                args += view.role.jmap; args += accounts
                "SELECT m.acc, m.thread, m.id, m.received, m.seen, m.flagged, m.has_att FROM message m " +
                    "JOIN msg_box b ON b.acc = m.acc AND b.msg = m.id JOIN mailbox x ON x.acc = b.acc AND x.id = b.box " +
                    "WHERE x.role = ? AND m.acc IN ($accMarks)"
            }
            is View.Box -> {
                args += view.acc; args += view.mailboxId
                "SELECT m.acc, m.thread, m.id, m.received, m.seen, m.flagged, m.has_att FROM message m " +
                    "JOIN msg_box b ON b.acc = m.acc AND b.msg = m.id WHERE b.acc = ? AND b.box = ?"
            }
            View.Flagged -> {
                args += accounts
                "SELECT m.acc, m.thread, m.id, m.received, m.seen, m.flagged, m.has_att FROM message m " +
                    "WHERE m.flagged = 1 AND m.acc IN ($accMarks) AND NOT EXISTS (SELECT 1 FROM msg_box b JOIN mailbox x ON x.acc = b.acc AND x.id = b.box " +
                    "WHERE b.acc = m.acc AND b.msg = m.id AND x.role IN ('trash','junk'))"
            }
        }
        val sql = "SELECT acc, thread, MAX(received) AS latest, COUNT(DISTINCT id), MIN(seen), MAX(flagged), MAX(has_att) " +
            "FROM ($scope) GROUP BY acc, thread ORDER BY latest DESC LIMIT $limit OFFSET $offset"
        data class G(val acc: String, val thread: String, val latest: Long, val count: Int, val unread: Boolean, val flagged: Boolean, val att: Boolean)
        val groups = readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            generateSequence {
                if (c.moveToNext()) G(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3), c.getInt(4) == 0, c.getInt(5) == 1, c.getInt(6) == 1) else null
            }.toList()
        }
        if (groups.isEmpty()) return emptyList()

        val details = HashMap<Pair<String, String>, Triple<String, String, String>>()
        val latestFrom = HashMap<Pair<String, String>, Pair<String, String>>()
        val senders = HashMap<Pair<String, String>, LinkedHashSet<String>>()
        groups.groupBy { it.acc }.forEach { (acc, gs) ->
            gs.map { it.thread }.chunked(300).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                readableDatabase.rawQuery(
                    "SELECT thread, id, subject, preview, from_name, received, from_addr FROM message WHERE acc = ? AND thread IN ($marks) ORDER BY received DESC",
                    arrayOf(acc) + chunk,
                ).use { c ->
                    while (c.moveToNext()) {
                        val k = acc to c.getString(0)
                        if (!details.containsKey(k)) {
                            details[k] = Triple(c.getString(1), c.getString(2), c.getString(3))
                            latestFrom[k] = c.getString(4) to c.getString(6)
                        }
                        senders.getOrPut(k) { LinkedHashSet() }.add(c.getString(4).substringBefore(' ').ifBlank { c.getString(4) })
                    }
                }
            }
        }
        return groups.mapNotNull { g ->
            val k = g.acc to g.thread
            val d = details[k] ?: return@mapNotNull null
            ThreadRow(
                acc = g.acc, threadId = g.thread, latestId = d.first, subject = d.second,
                senders = senders[k]?.filter { it.isNotBlank() }?.take(3)?.joinToString(", ").orEmpty(),
                preview = d.third, received = g.latest, count = g.count, unread = g.unread,
                flagged = g.flagged, hasAttachment = g.att,
                fromName = latestFrom[k]?.first.orEmpty(), fromEmail = latestFrom[k]?.second.orEmpty(),
            )
        }
    }

    /** Oldest message date the view holds for [acc], to page further back from. */
    fun oldestIn(acc: String, boxIds: List<String>): Long? {
        if (boxIds.isEmpty()) return null
        val marks = boxIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT MIN(m.received) FROM message m JOIN msg_box b ON b.acc = m.acc AND b.msg = m.id WHERE b.acc = ? AND b.box IN ($marks)",
            arrayOf(acc) + boxIds,
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }

    // ---------- identities ----------

    data class Identity(val acc: String, val id: String, val name: String, val email: String, val replyTo: String, val bcc: String, val signature: String)

    fun replaceIdentities(acc: String, list: List<Identity>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("identity", "acc = ?", arrayOf(acc))
            list.forEach {
                db.insert("identity", null, ContentValues().apply {
                    put("acc", it.acc); put("id", it.id); put("name", it.name); put("email", it.email)
                    put("reply_to", it.replyTo); put("bcc", it.bcc); put("signature", it.signature)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun identities(acc: String? = null): List<Identity> =
        readableDatabase.rawQuery(
            "SELECT acc, id, name, email, reply_to, bcc, signature FROM identity" + (if (acc != null) " WHERE acc = ?" else "") + " ORDER BY acc, email",
            acc?.let { arrayOf(it) },
        ).use { c -> generateSequence { if (c.moveToNext()) Identity(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)) else null }.toList() }

    // ---------- ops queue ----------

    data class Op(val id: Long, val acc: String, val kind: String, val payload: String, val tries: Int)

    fun enqueueOp(acc: String, kind: String, payload: String) {
        writableDatabase.insert("ops", null, ContentValues().apply {
            put("acc", acc); put("kind", kind); put("payload", payload); put("created", System.currentTimeMillis())
        })
    }

    fun ops(): List<Op> = readableDatabase.rawQuery("SELECT id, acc, kind, payload, tries FROM ops ORDER BY id ASC LIMIT 200", null).use { c ->
        generateSequence { if (c.moveToNext()) Op(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4)) else null }.toList()
    }

    fun opDone(id: Long) {
        writableDatabase.delete("ops", "id = ?", arrayOf(id.toString()))
    }

    fun opFailed(id: Long) {
        writableDatabase.execSQL("UPDATE ops SET tries = tries + 1 WHERE id = ?", arrayOf(id))
    }

    // ---------- index queue ----------

    fun queueIndex(acc: String, ids: Collection<String>, op: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach {
                db.insertWithOnConflict("index_queue", null, ContentValues().apply {
                    put("acc", acc); put("msg", it); put("op", op)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun indexBatch(acc: String, op: String, limit: Int): List<String> =
        readableDatabase.rawQuery("SELECT msg FROM index_queue WHERE acc = ? AND op = ? LIMIT $limit", arrayOf(acc, op)).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }

    fun indexDone(acc: String, ids: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("index_queue", "acc = ? AND msg = ?", arrayOf(acc, it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun indexPending(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM index_queue", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun clearIndexQueue() {
        writableDatabase.delete("index_queue", null, null)
    }

    // ---------- notifications ----------

    /** True the first time a message is seen by the notifier. */
    fun markNotified(acc: String, id: String): Boolean =
        writableDatabase.insertWithOnConflict("notified", null, ContentValues().apply {
            put("acc", acc); put("msg", id); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun pruneNotified() {
        writableDatabase.delete("notified", "at < ?", arrayOf((System.currentTimeMillis() - 30L * 86_400_000).toString()))
    }

    companion object {
        private const val MSG_COLS = "acc, id, thread, seen, flagged, draft, answered, received, subject, from_json, to_json, cc_json, " +
            "bcc_json, reply_to_json, preview, has_att, size, message_id, in_reply_to, refs, body_text, body_html, atts_json"

        @Volatile
        private var instance: MailDb? = null

        fun get(context: Context): MailDb = instance ?: synchronized(this) { instance ?: MailDb(context).also { instance = it } }
    }
}
