package com.opensolr.mail.jmap

import android.content.Context
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Fastmail Notes: messages in the special "Notes" mailbox, in the Apple Notes format Fastmail reads
 * and writes. A message cannot be changed, so saving a note writes a new one with the same note id
 * and removes the old one — exactly what Apple Notes does over IMAP.
 */
class Notes(private val context: Context, private val account: MailAccount) {

    data class Note(val id: String, val title: String, val preview: String, val updated: Long, val pinned: Boolean)

    private val jmap = Jmap(context, account)

    private fun notesBoxId(): String? = MailDb.get(context).mailboxes(account.key)
        .firstOrNull { it.parentId == null && it.role == null && it.name.equals("Notes", true) }?.id

    suspend fun list(): List<Note> {
        val box = notesBoxId() ?: return emptyList()
        val b = Jmap.Batch()
        val q = b.add(
            "Email/query",
            JSONObject().put("accountId", jmap.accountId).put("filter", JSONObject().put("inMailbox", box))
                .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false))).put("limit", 1000),
        )
        val g = b.add(
            "Email/get",
            JSONObject().put("accountId", jmap.accountId)
                .put("#ids", JSONObject().put("resultOf", q).put("name", "Email/query").put("path", "/ids"))
                .put("properties", JSONArray(listOf("id", "subject", "preview", "receivedAt", "keywords"))),
        )
        val list = jmap.send(b).get(g).getJSONArray("list")
        return (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            Note(
                id = o.getString("id"),
                title = o.optString("subject").takeIf { it != "null" }.orEmpty(),
                preview = o.optString("preview").takeIf { it != "null" }.orEmpty(),
                updated = MailSync.parseDate(o.optString("receivedAt")),
                pinned = o.optJSONObject("keywords")?.optBoolean("\$flagged") == true,
            )
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
    }

    data class Content(val title: String, val text: String, val html: String, val uuid: String, val created: String, val pinned: Boolean)

    suspend fun open(id: String): Content? {
        val r = jmap.call(
            "Email/get",
            JSONObject().put("ids", JSONArray().put(id))
                .put("properties", JSONArray(listOf("id", "subject", "keywords", "textBody", "htmlBody", "bodyValues",
                    "header:X-Universally-Unique-Identifier:asText", "header:X-Mail-Created-Date:asText")))
                .put("fetchTextBodyValues", true).put("fetchHTMLBodyValues", true),
        )
        val o = r.getJSONArray("list").optJSONObject(0) ?: return null
        val body = MailSync.parseBody(o)
        val title = o.optString("subject").takeIf { it != "null" }.orEmpty()
        val lines = body.text.trim().lines()
        val text = if (title.isNotBlank() && lines.firstOrNull()?.trim() == title.trim()) lines.drop(1).joinToString("\n").trimStart('\n') else body.text.trim()
        return Content(
            title = title,
            text = text,
            html = body.html,
            uuid = o.optString("header:X-Universally-Unique-Identifier:asText").trim().takeIf { it.isNotEmpty() && it != "null" } ?: UUID.randomUUID().toString().uppercase(),
            created = o.optString("header:X-Mail-Created-Date:asText").trim().takeIf { it.isNotEmpty() && it != "null" } ?: rfc2822(Date()),
            pinned = o.optJSONObject("keywords")?.optBoolean("\$flagged") == true,
        )
    }

    /** Writes the note; returns the new message id. [previous] is removed once the new one exists. */
    suspend fun save(previous: String?, title: String, text: String, uuid: String?, created: String?, pinned: Boolean): String {
        val box = notesBoxId() ?: throw Jmap.JmapError("noNotes", "This account has no Notes folder")
        val identity = MailDb.get(context).identities(account.key).firstOrNull()
        val full = if (title.isBlank()) text else title + "\n" + text
        val html = "<html><head></head><body>" +
            full.lines().joinToString("") { l -> if (l.isBlank()) "<div><br></div>" else "<div>" + Html.escape(l) + "</div>" } +
            "</body></html>"
        val kw = JSONObject().put("\$seen", true)
        if (pinned) kw.put("\$flagged", true)
        val email = JSONObject()
            .put("mailboxIds", JSONObject().put(box, true))
            .put("keywords", kw)
            .put("subject", title.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120) })
            .put("from", JSONArray().put(JSONObject().put("name", identity?.name.orEmpty()).put("email", identity?.email ?: account.username)))
            .put("header:X-Uniform-Type-Identifier:asText", "com.apple.mail-note")
            .put("header:X-Universally-Unique-Identifier:asText", uuid ?: UUID.randomUUID().toString().uppercase())
            .put("header:X-Mail-Created-Date:asText", created ?: rfc2822(Date()))
            .put("bodyValues", JSONObject().put("h", JSONObject().put("value", html)))
            .put("htmlBody", JSONArray().put(JSONObject().put("partId", "h").put("type", "text/html")))
        val set = JSONObject().put("create", JSONObject().put("n", email))
        if (previous != null) set.put("destroy", JSONArray().put(previous))
        val r = jmap.call("Email/set", set)
        r.optJSONObject("notCreated")?.optJSONObject("n")?.let { throw Jmap.JmapError(it.optString("type"), it.optString("description")) }
        return r.getJSONObject("created").getJSONObject("n").getString("id")
    }

    suspend fun delete(id: String) {
        jmap.call("Email/set", JSONObject().put("destroy", JSONArray().put(id)))
    }

    private fun rfc2822(d: Date) = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(d)
}
