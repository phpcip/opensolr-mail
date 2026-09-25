package com.opensolr.mail.search

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.jmap.Jmap
import com.opensolr.mail.jmap.MailSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The classic search on Fastmail's own servers (JMAP Email/query), chosen in Settings: words only,
 * newest first, no filters or grouping, one request per account, the accounts merged by date.
 */
class FastmailSearch(private val context: Context) {

    private val db = MailDb.get(context)
    private val store = AccountStore.get(context)

    /** Where each account's next page starts, and how many results it has. */
    data class Cursor(val positions: Map<String, Int>, val totals: Map<String, Int>) {
        val more: Boolean get() = positions.any { (acc, at) -> at < (totals[acc] ?: 0) }
    }

    private class Page(val account: MailAccount, val start: Int, val total: Int, val hits: List<MailSearch.Hit>)

    suspend fun search(text: String, cursor: Cursor? = null, rows: Int = 40): Pair<MailSearch.Result, Cursor> = coroutineScope {
        val jobs = store.all().mapNotNull { a ->
            val start = cursor?.positions?.get(a.key) ?: 0
            if (cursor != null && start >= (cursor.totals[a.key] ?: 0)) return@mapNotNull null
            val filter = filterOf(a, text)
            async { runCatching { page(a, filter, text.isNotBlank(), start, rows) } }
        }
        val answers = jobs.awaitAll()
        answers.forEach { r -> (r.exceptionOrNull() as? CancellationException)?.let { throw it } }
        val pages = answers.mapNotNull { it.getOrNull() }
        if (pages.isEmpty()) answers.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }

        // A page may be kept only as far as no unread page of another account can hold anything newer.
        val cut = pages.filter { it.start + it.hits.size < it.total && it.hits.isNotEmpty() }.maxOfOrNull { it.hits.last().received }
        val kept = pages.flatMap { pg -> if (cut == null) pg.hits else pg.hits.filter { it.received >= cut } }
            .sortedByDescending { it.received }
        val positions = HashMap(cursor?.positions.orEmpty())
        val totals = HashMap(cursor?.totals.orEmpty())
        pages.forEach { pg ->
            positions[pg.account.key] = pg.start + kept.count { it.acc == pg.account.key }
            totals[pg.account.key] = pg.total
        }
        val total = totals.values.sum().toLong()
        MailSearch.Result(kept, emptyList(), total, false, emptyMap(), emptyMap(), emptyList(), emptyMap(), fetched = kept.size) to Cursor(positions, totals)
    }

    /** One page of one account: the matching conversations, their newest message, and the words found in it, in one request. */
    private suspend fun page(a: MailAccount, filter: Any, words: Boolean, start: Int, rows: Int): Page {
        val jmap = Jmap(context, a)
        val b = Jmap.Batch()
        val q = b.add(
            "Email/query",
            JSONObject().put("accountId", jmap.accountId).put("filter", filter)
                .put("sort", JSONArray().put(JSONObject().put("property", "receivedAt").put("isAscending", false)))
                .put("collapseThreads", true).put("position", start).put("limit", rows).put("calculateTotal", true),
        )
        val ids = JSONObject().put("resultOf", q).put("name", "Email/query").put("path", "/ids")
        val g = b.add(
            "Email/get",
            JSONObject().put("accountId", jmap.accountId).put("#ids", ids)
                .put("properties", JSONArray(listOf("id", "threadId", "mailboxIds", "keywords", "receivedAt", "subject", "from", "preview", "hasAttachment", "messageId"))),
        )
        val s = if (words) b.add("SearchSnippet/get", JSONObject().put("accountId", jmap.accountId).put("filter", filter).put("#emailIds", ids)) else null
        val r = jmap.send(b)
        val query = r.get(q)
        val order = query.optJSONArray("ids") ?: JSONArray()
        val list = r.get(g).optJSONArray("list") ?: JSONArray()
        val emails = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.optString("id") }
        // The words found are marked by Fastmail; the list shows them the way it shows the index's.
        val snippets = HashMap<String, String>()
        s?.let { id -> r.opt(id)?.optJSONArray("list") }?.let { sl ->
            for (i in 0 until sl.length()) {
                val one = sl.getJSONObject(i)
                val preview = one.optString("preview").takeIf { !one.isNull("preview") && it.isNotBlank() } ?: continue
                snippets[one.optString("emailId")] = preview.replace("<mark>", "<em>").replace("</mark>", "</em>")
            }
        }
        val boxes = withContext(Dispatchers.IO) { db.mailboxes(a.key) }.associateBy { it.id }
        val hits = (0 until order.length()).mapNotNull { i -> emails[order.optString(i)] }.map { e ->
            val from = e.optJSONArray("from")?.optJSONObject(0)
            val fromEmail = from?.optString("email").orEmpty()
            val keywords = e.optJSONObject("keywords")
            val inBoxes = e.optJSONObject("mailboxIds")?.keys()?.asSequence()?.mapNotNull { boxes[it] }?.toList().orEmpty()
            MailSearch.Hit(
                acc = a.key, emailId = e.optString("id"), threadId = e.optString("threadId"),
                subject = e.optString("subject"), from = from?.optString("name")?.takeIf { it.isNotBlank() && !from.isNull("name") } ?: fromEmail,
                fromEmail = fromEmail, received = MailSync.parseDate(e.optString("receivedAt")),
                snippet = snippets[e.optString("id")] ?: e.optString("preview"),
                seen = keywords?.optBoolean("\$seen") == true, flagged = keywords?.optBoolean("\$flagged") == true,
                hasAttachment = e.optBoolean("hasAttachment"), score = 0.0,
                bin = inBoxes.firstOrNull { it.role == "trash" || it.role == "junk" }?.role.orEmpty(),
                messageId = e.optJSONArray("messageId")?.optString(0).orEmpty(),
                folders = inBoxes.map { it.name },
            )
        }
        // One line per conversation, with the size of the whole conversation as this phone holds it.
        val sizes = withContext(Dispatchers.IO) { runCatching { db.threadSizes(a.key, hits.map { it.threadId }.filter { it.isNotEmpty() }) }.getOrDefault(emptyMap()) }
        return Page(a, start, query.optInt("total", start + hits.size), hits.map { it.copy(threadCount = sizes[it.threadId] ?: 1) })
    }

    /** The words typed, with the same +word / -word / "phrase" rules as the Opensolr search; Trash and Junk stay out. */
    private suspend fun filterOf(a: MailAccount, text: String): Any {
        val all = ArrayList<JSONObject>()
        val q = text.trim().take(500)
        if (q.isNotEmpty()) {
            val ops = SearchOperators.parse(q)
            if (ops.base.isNotBlank()) all += JSONObject().put("text", ops.base)
            ops.required.forEach { all += JSONObject().put("text", it) }
            ops.excluded.forEach { all += not(JSONObject().put("text", it)) }
        }
        val bins = withContext(Dispatchers.IO) { db.mailboxes(a.key) }.filter { it.role == "trash" || it.role == "junk" }.map { it.id }
        if (bins.isNotEmpty()) all += JSONObject().put("inMailboxOtherThan", JSONArray(bins))
        return when (all.size) {
            0 -> JSONObject.NULL
            1 -> all[0]
            else -> JSONObject().put("operator", "AND").put("conditions", JSONArray(all))
        }
    }

    private fun not(c: JSONObject) = JSONObject().put("operator", "NOT").put("conditions", JSONArray().put(c))
}
