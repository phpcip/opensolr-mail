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

    override fun onOpen(db: SQLiteDatabase) {
        createHidden(db)
    }

    /** Conversations deleted here, kept out of search until the index has caught up; [forever] when destroyed. */
    private fun createHidden(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden (acc TEXT NOT NULL, thread TEXT NOT NULL, until INTEGER NOT NULL, forever INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (acc, thread))")
        // Senders reported as spam: whatever they send next goes straight to Junk.
        db.execSQL("CREATE TABLE IF NOT EXISTS blocked (acc TEXT NOT NULL, email TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (acc, email))")
        // Recipient suggestions read from the mail index, per typed prefix.
        db.execSQL("CREATE TABLE IF NOT EXISTS suggest_cache (k TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL, at INTEGER NOT NULL)")
        // Fastmail address books (their ctag: an unchanged book is not read again) and their cards.
        db.execSQL("CREATE TABLE IF NOT EXISTS fm_book (acc TEXT NOT NULL, href TEXT NOT NULL, ctag TEXT NOT NULL, PRIMARY KEY (acc, href))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS fm_card (acc TEXT NOT NULL, href TEXT NOT NULL, book TEXT NOT NULL, etag TEXT NOT NULL, name TEXT NOT NULL, " +
                "emails TEXT NOT NULL, phones TEXT NOT NULL, addrs TEXT NOT NULL, photo BLOB, PRIMARY KEY (acc, href))"
        )
        // Everyone to write to, merged from every source: read by the contacts in pages, in name order, searched through FTS.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS person (id INTEGER PRIMARY KEY, name TEXT NOT NULL, sort TEXT NOT NULL, letter TEXT NOT NULL, " +
                "emails TEXT NOT NULL, phones TEXT NOT NULL, addrs TEXT NOT NULL, photo TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS person_sort ON person (sort, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS person_letter ON person (letter)")
        db.execSQL("CREATE TABLE IF NOT EXISTS person_email (email TEXT NOT NULL PRIMARY KEY, person INTEGER NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS person_fts USING fts4(search)")
    }

    /** One person as the contacts show them. */
    data class PersonRow(val id: Long, val name: String, val emails: List<String>, val phones: List<String>, val addresses: List<String>, val photo: String?)

    private fun jsonList(json: String): List<String> = runCatching { org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())

    /**
     * Rebuilds the people in one transaction: [fill] adds everyone, and a person sharing an address with one
     * already added is merged into it, the first to arrive keeping its name and picture. Readers keep the old
     * list until the new one is committed.
     */
    fun rebuildPeople(fill: (add: (name: String, emails: List<String>, phones: List<String>, addrs: List<String>, photo: String?) -> Unit) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("person", null, null)
            db.delete("person_email", null, null)
            db.delete("person_fts", null, null)
            fill { name, emails, phones, addrs, photo ->
                val keys = emails.map { it.trim().lowercase() }.filter { it.contains('@') }.distinct()
                if (keys.isEmpty()) return@fill
                val existing = keys.firstNotNullOfOrNull { k ->
                    db.rawQuery("SELECT person FROM person_email WHERE email = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                }
                if (existing == null) {
                    val cleanEmails = emails.map { it.trim() }.filter { it.contains('@') }.distinctBy { it.lowercase() }
                    val id = db.insert("person", null, personValues(name, cleanEmails, phones.distinct(), addrs.distinct(), photo))
                    keys.forEach { k -> db.insertWithOnConflict("person_email", null, ContentValues().apply { put("email", k); put("person", id) }, SQLiteDatabase.CONFLICT_IGNORE) }
                    db.insert("person_fts", null, ContentValues().apply { put("docid", id); put("search", searchText(name, cleanEmails, phones)) })
                } else {
                    val old = db.rawQuery("SELECT name, emails, phones, addrs, photo FROM person WHERE id = ?", arrayOf(existing.toString())).use { c ->
                        if (c.moveToFirst()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null
                    } ?: return@fill
                    val n = old[0]!!.ifBlank { name }
                    val e = (jsonList(old[1]!!) + emails.map { it.trim() }.filter { it.contains('@') }).distinctBy { it.lowercase() }
                    val ph = (jsonList(old[2]!!) + phones).distinctBy { it.filter(Char::isDigit) }
                    val ad = (jsonList(old[3]!!) + addrs).distinct()
                    db.update("person", personValues(n, e, ph, ad, old[4] ?: photo), "id = ?", arrayOf(existing.toString()))
                    keys.forEach { k -> db.insertWithOnConflict("person_email", null, ContentValues().apply { put("email", k); put("person", existing) }, SQLiteDatabase.CONFLICT_IGNORE) }
                    db.update("person_fts", ContentValues().apply { put("search", searchText(n, e, ph)) }, "docid = ?", arrayOf(existing.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun personValues(name: String, emails: List<String>, phones: List<String>, addrs: List<String>, photo: String?): ContentValues {
        val folded = fold(name.ifBlank { emails.firstOrNull().orEmpty() })
        val first = folded.firstOrNull()
        val letter = if (first != null && first in 'a'..'z') first.uppercaseChar().toString() else "#"
        return ContentValues().apply {
            put("name", name)
            // Letters first, A to Z; everything else (digits, symbols, other scripts) after Z under #.
            put("sort", if (letter == "#") "{" + folded else folded)
            put("letter", letter)
            put("emails", org.json.JSONArray(emails).toString())
            put("phones", org.json.JSONArray(phones).toString())
            put("addrs", org.json.JSONArray(addrs).toString())
            put("photo", photo)
        }
    }

    private fun searchText(name: String, emails: List<String>, phones: List<String>): String =
        (listOf(fold(name)) + emails.map { fold(it) } + phones.map { it.filter(Char::isDigit) }).joinToString(" ")

    /** An FTS query from typed words: every word a prefix, only letters and digits (no FTS syntax from the reader). */
    private fun ftsQuery(typed: String): String? =
        fold(typed).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(8).joinToString(" ") { "$it*" }.ifBlank { null }

    /** How many people each letter holds, in list order; [typed] narrows them. */
    fun peopleLetters(typed: String): List<Pair<String, Int>> {
        val q = ftsQuery(typed)
        val where = if (q != null) " WHERE id IN (SELECT docid FROM person_fts WHERE search MATCH ?)" else ""
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery("SELECT letter, COUNT(*) FROM person$where GROUP BY letter ORDER BY letter = '#', letter", if (q != null) arrayOf(q) else null).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getInt(1)
        }
        return out
    }

    /** One page of people in name order, [typed] narrowing them the same way as [peopleLetters]. */
    fun peoplePage(typed: String, offset: Int, limit: Int): List<PersonRow> {
        val q = ftsQuery(typed)
        val where = if (q != null) " WHERE id IN (SELECT docid FROM person_fts WHERE search MATCH ?)" else ""
        val args = (if (q != null) listOf(q) else emptyList()) + listOf(limit.coerceIn(1, 500).toString(), offset.coerceAtLeast(0).toString())
        val out = ArrayList<PersonRow>()
        readableDatabase.rawQuery("SELECT id, name, emails, phones, addrs, photo FROM person$where ORDER BY sort, id LIMIT ? OFFSET ?", args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += PersonRow(c.getLong(0), c.getString(1), jsonList(c.getString(2)), jsonList(c.getString(3)), jsonList(c.getString(4)), c.getString(5))
        }
        return out
    }

    fun peopleCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM person", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Lower case, accents off: how names and addresses are sorted and searched. */
    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

    /** Moves whenever the stored Fastmail cards change, so a copy read from them knows it is out of date. */
    @Volatile var fmStamp = 0L
        private set

    /** One Fastmail card as the contacts read it: the photo stays in the table until a row shows it. */
    data class FmCard(val acc: String, val href: String, val name: String, val emails: List<String>, val phones: List<String>, val addresses: List<String>, val hasPhoto: Boolean)

    fun fmBookCtag(acc: String, href: String): String? =
        readableDatabase.rawQuery("SELECT ctag FROM fm_book WHERE acc = ? AND href = ?", arrayOf(acc, href)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun fmCardEtags(acc: String, book: String): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT href, etag FROM fm_card WHERE acc = ? AND book = ?", arrayOf(acc, book)).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /** Stores what changed in one book, drops what went, and records the book's ctag, in one transaction. */
    fun fmStoreBook(acc: String, book: String, ctag: String, cards: List<Pair<Pair<String, String>, com.opensolr.mail.dav.VCard.Card>>, removed: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            removed.forEach { db.delete("fm_card", "acc = ? AND href = ?", arrayOf(acc, it)) }
            cards.forEach { (key, c) ->
                db.insertWithOnConflict("fm_card", null, ContentValues().apply {
                    put("acc", acc); put("href", key.first); put("book", book); put("etag", key.second); put("name", c.name)
                    put("emails", org.json.JSONArray(c.emails).toString()); put("phones", org.json.JSONArray(c.phones).toString())
                    put("addrs", org.json.JSONArray(c.addresses).toString()); put("photo", c.photo)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.insertWithOnConflict("fm_book", null, ContentValues().apply { put("acc", acc); put("href", book); put("ctag", ctag) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        fmStamp++
    }

    /** Books of [acc] that are gone on the server leave, with their cards. */
    fun fmKeepBooks(acc: String, books: Collection<String>) {
        val gone = ArrayList<String>()
        readableDatabase.rawQuery("SELECT href FROM fm_book WHERE acc = ?", arrayOf(acc)).use { c -> while (c.moveToNext()) if (c.getString(0) !in books) gone += c.getString(0) }
        if (gone.isEmpty()) return
        val db = writableDatabase
        gone.forEach { b -> db.delete("fm_card", "acc = ? AND book = ?", arrayOf(acc, b)); db.delete("fm_book", "acc = ? AND href = ?", arrayOf(acc, b)) }
        fmStamp++
    }

    fun fmCards(): List<FmCard> {
        fun list(json: String) = runCatching { org.json.JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        val out = ArrayList<FmCard>()
        readableDatabase.rawQuery("SELECT acc, href, name, emails, phones, addrs, photo IS NOT NULL FROM fm_card", null).use { c ->
            while (c.moveToNext()) out += FmCard(c.getString(0), c.getString(1), c.getString(2), list(c.getString(3)), list(c.getString(4)), list(c.getString(5)), c.getInt(6) == 1)
        }
        return out
    }

    fun fmPhoto(acc: String, href: String): ByteArray? =
        readableDatabase.rawQuery("SELECT photo FROM fm_card WHERE acc = ? AND href = ?", arrayOf(acc, href)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }

    fun suggestCached(key: String, notBefore: Long): String? =
        readableDatabase.rawQuery("SELECT json FROM suggest_cache WHERE k = ? AND at >= ?", arrayOf(key, notBefore.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Stores one prefix and drops the ones past their time, so the table never grows without bound. */
    fun suggestStore(key: String, json: String, expiredBefore: Long) {
        val w = writableDatabase
        w.insertWithOnConflict("suggest_cache", null, ContentValues().apply { put("k", key); put("json", json); put("at", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        w.delete("suggest_cache", "at < ?", arrayOf(expiredBefore.toString()))
    }

    fun block(acc: String, emails: Collection<String>) {
        val now = System.currentTimeMillis()
        emails.map { it.trim().lowercase() }.filter { it.contains('@') }.distinct().forEach { e ->
            writableDatabase.insertWithOnConflict("blocked", null, ContentValues().apply { put("acc", acc); put("email", e); put("at", now) }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun unblock(acc: String, emails: Collection<String>) {
        emails.map { it.trim().lowercase() }.distinct().forEach { e -> writableDatabase.delete("blocked", "acc = ? AND email = ?", arrayOf(acc, e)) }
    }

    fun blocked(acc: String): Set<String> =
        readableDatabase.rawQuery("SELECT email FROM blocked WHERE acc = ?", arrayOf(acc)).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toHashSet()
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
        createHidden(db)
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

    /** Unread messages of the Flagged view: flagged, on these accounts, outside Trash and Junk. */
    fun unreadFlagged(accounts: List<String>): Int {
        if (accounts.isEmpty()) return 0
        val marks = accounts.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM message m WHERE m.flagged = 1 AND m.seen = 0 AND m.acc IN ($marks) AND NOT EXISTS (SELECT 1 FROM msg_box b " +
                "JOIN mailbox x ON x.acc = b.acc AND x.id = b.box WHERE b.acc = m.acc AND b.msg = m.id AND x.role IN ('trash','junk'))",
            accounts.toTypedArray(),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
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
            listOf("mailbox", "message", "msg_box", "state", "identity", "ops", "index_queue", "notified", "hidden", "blocked", "fm_book", "fm_card").forEach {
                db.delete(it, "acc = ?", arrayOf(acc))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        fmStamp++
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
        readableDatabase.rawQuery("SELECT $MSG_COLS FROM message WHERE acc = ? AND id = ?", arrayOf(acc, id)).roomy().use { c ->
            if (c.moveToFirst()) readMessage(c) else null
        }?.let { withBoxes(listOf(it)).first() }

    fun thread(acc: String, threadId: String): List<Message> =
        withBoxes(readableDatabase.rawQuery("SELECT $MSG_COLS FROM message WHERE acc = ? AND thread = ? ORDER BY received ASC", arrayOf(acc, threadId)).roomy().use { c ->
            generateSequence { if (c.moveToNext()) readMessage(c) else null }.toList()
        })

    /** Many messages of one account, in chunks, with their mailboxes. */
    fun messages(acc: String, ids: List<String>): List<Message> {
        val out = ArrayList<Message>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT $MSG_COLS FROM message WHERE acc = ? AND id IN ($marks)", arrayOf(acc) + chunk).roomy().use { c ->
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
    fun threads(view: View, accounts: Set<String>, limit: Int, offset: Int, flagged: Boolean? = null): List<ThreadRow> {
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
        // A conversation sorts by its newest message anywhere (a reply in Sent brings it back up), and
        // counts every message it holds; both read through the (acc, thread, received) index.
        val sql = "SELECT g.acc, g.thread, " +
            "(SELECT MAX(t.received) FROM message t WHERE t.acc = g.acc AND t.thread = g.thread) AS latest, " +
            "(SELECT COUNT(*) FROM message t WHERE t.acc = g.acc AND t.thread = g.thread), g.s, g.f, g.a " +
            "FROM (SELECT acc, thread, MIN(seen) AS s, MAX(flagged) AS f, MAX(has_att) AS a FROM ($scope) GROUP BY acc, thread" +
            (when (flagged) { true -> " HAVING MAX(flagged) = 1"; false -> " HAVING MAX(flagged) = 0"; null -> "" }) +
            ") g ORDER BY latest DESC LIMIT $limit OFFSET $offset"
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

    /** How many messages each conversation holds locally, for [threads] of one account, in one query. */
    fun threadSizes(acc: String, threads: Collection<String>): Map<String, Int> {
        if (threads.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        threads.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT thread, COUNT(*) FROM message WHERE acc = ? AND thread IN ($marks) GROUP BY thread", arrayOf(acc) + chunk).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            }
        }
        return out
    }

    /** Keeps [threads] of [acc] out of search for a day, by then long written to the index. */
    fun hide(acc: String, threads: Collection<String>, forever: Boolean) {
        if (threads.isEmpty()) return
        val until = System.currentTimeMillis() + 86_400_000L
        val db = writableDatabase
        db.beginTransaction()
        try {
            threads.forEach { t ->
                db.insertWithOnConflict("hidden", null, ContentValues().apply {
                    put("acc", acc); put("thread", t); put("until", until); put("forever", if (forever) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        touch()
    }

    fun unhide(acc: String, threads: Collection<String>) {
        if (threads.isEmpty()) return
        threads.distinct().chunked(400).forEach { chunk ->
            writableDatabase.delete("hidden", "acc = ? AND thread IN (${chunk.joinToString(",") { "?" }})", arrayOf(acc) + chunk)
        }
        touch()
    }

    /** Conversations to keep out of search now, per account, with whether each is gone for good; expired rows go. */
    fun hidden(): List<Triple<String, String, Boolean>> {
        writableDatabase.delete("hidden", "until < ?", arrayOf(System.currentTimeMillis().toString()))
        return readableDatabase.rawQuery("SELECT acc, thread, forever FROM hidden", null).use { c ->
            generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getString(1), c.getInt(2) != 0) else null }.toList()
        }
    }

    /** The conversations the messages [ids] of [acc] belong to. */
    fun threadsOf(acc: String, ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<String>()
        ids.distinct().chunked(400).forEach { chunk ->
            readableDatabase.rawQuery("SELECT DISTINCT thread FROM message WHERE acc = ? AND id IN (${chunk.joinToString(",") { "?" }})", arrayOf(acc) + chunk).use { c ->
                while (c.moveToNext()) out += c.getString(0)
            }
        }
        return out
    }

    /** The Message-ID of each of [ids] of [acc]. */
    fun messageIds(acc: String, ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        ids.distinct().chunked(400).forEach { chunk ->
            readableDatabase.rawQuery("SELECT id, message_id FROM message WHERE acc = ? AND id IN (${chunk.joinToString(",") { "?" }})", arrayOf(acc) + chunk).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            }
        }
        return out
    }

    /** The names of the folders each message lies in, as this phone holds them. One query per 400. */
    fun folderNamesOf(acc: String, ids: Collection<String>): Map<String, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<String>>()
        ids.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT b.msg, x.name FROM msg_box b JOIN mailbox x ON x.acc = b.acc AND x.id = b.box WHERE b.acc = ? AND b.msg IN ($marks) ORDER BY x.sort, x.name COLLATE NOCASE",
                arrayOf(acc) + chunk,
            ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() }.add(c.getString(1)) }
        }
        return out
    }

    /** Of each conversation held here: whether any message is flagged and whether any is unread. One query per 400. */
    fun threadStates(acc: String, threads: Collection<String>): Map<String, Pair<Boolean, Boolean>> {
        if (threads.isEmpty()) return emptyMap()
        val out = HashMap<String, Pair<Boolean, Boolean>>()
        threads.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT thread, MAX(flagged), MIN(seen) FROM message WHERE acc = ? AND thread IN ($marks) GROUP BY thread", arrayOf(acc) + chunk).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = (c.getInt(1) != 0) to (c.getInt(2) == 0)
            }
        }
        return out
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
        /**
         * A message row carries its whole body, which can pass the 2 MB a cursor window holds by default and would
         * throw on reading: rows with bodies are read through a window large enough for them.
         */
        private fun android.database.Cursor.roomy(): android.database.Cursor {
            if (android.os.Build.VERSION.SDK_INT >= 28 && this is android.database.AbstractWindowedCursor) {
                runCatching { window = android.database.CursorWindow(null, BODY_WINDOW) }
            }
            return this
        }
        private const val BODY_WINDOW = 24L * 1024 * 1024
        private const val MSG_COLS = "acc, id, thread, seen, flagged, draft, answered, received, subject, from_json, to_json, cc_json, " +
            "bcc_json, reply_to_json, preview, has_att, size, message_id, in_reply_to, refs, body_text, body_html, atts_json"

        @Volatile
        private var instance: MailDb? = null

        fun get(context: Context): MailDb = instance ?: synchronized(this) { instance ?: MailDb(context).also { instance = it } }
    }
}
