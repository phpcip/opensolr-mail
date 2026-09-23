package com.opensolr.mail.search

import android.content.Context
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.index.MailIndex
import com.opensolr.mail.index.MailIndexer
import com.opensolr.mail.index.SolrClient
import com.opensolr.mail.jmap.MailSync
import com.opensolr.mail.net.OpensolrApi
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Search and browse over every account at once, in the Opensolr Index: the same paradigm as
 * search.opensolr.com and Opensolr Photos — words and meaning fused by {!hybrid}, a Fresh
 * multiplier on the date, facets that filter, grouping, and the AI answer over the best matches.
 */
class MailSearch(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)
    private val db = com.opensolr.mail.data.MailDb.get(context)
    private val store = com.opensolr.mail.data.AccountStore.get(context)

    data class Hit(
        val acc: String,
        val emailId: String,
        val threadId: String,
        /** Messages in the conversation: the local count, or at least the matches found in it. */
        val threadCount: Int = 1,
        val subject: String,
        val from: String,
        val fromEmail: String,
        val received: Long,
        val snippet: String,
        val seen: Boolean,
        val flagged: Boolean,
        val hasAttachment: Boolean,
        val score: Double,
        /** The document in the index this row came from. */
        val docId: String = "",
    )

    data class DateRange(val from: Long, val to: Long)

    /** Every filter the search can apply; facet values are OR-ed inside a facet and AND-ed across facets. */
    data class Filters(
        val facets: Map<String, Set<String>> = emptyMap(),
        val dates: DateRange? = null,
        val unread: Boolean = false,
        val flagged: Boolean = false,
        val answered: Boolean = false,
        val attachments: Boolean = false,
        val attachmentText: Boolean = false,
        val includeTrash: Boolean = false,
    ) {
        fun values(field: String): Set<String> = facets[field].orEmpty()

        fun toggled(field: String, value: String): Filters {
            val now = values(field)
            val next = if (value in now) now - value else now + value
            return copy(facets = if (next.isEmpty()) facets - field else facets + (field to next))
        }

        val count: Int
            get() = facets.values.sumOf { it.size } + listOf(dates != null, unread, flagged, answered, attachments, attachmentText, includeTrash).count { it }

        companion object {
            /** Facet fields, in the order the filter sheet shows them. */
            val FACETS = listOf(
                "from_s", "to_ss", "domains_ss", "account_email_s", "mailbox_name_ss", "year_i", "attachment_ext_ss", "weekday_i",
            )
        }
    }

    enum class GroupBy(val field: String?) {
        NONE(null), DATE("month_s"), DAY("day_s"), SENDER("from_s"), COMPANY("from_domain_s"), ACCOUNT("account_email_s"),
    }

    data class Facet(val value: String, val count: Int)

    data class Group(val value: String, val total: Long, val hits: List<Hit>)

    data class Result(
        val hits: List<Hit>,
        val groups: List<Group>,
        val total: Long,
        val smart: Boolean,
        val facets: Map<String, List<Facet>>,
        /** The name each address is known by: the one it carries most often. */
        val names: Map<String, String>,
        val docs: List<AiPrompt.Doc>,
        val highlights: Map<String, Map<String, List<String>>>,
    )


    suspend fun search(text: String, filters: Filters, groupBy: GroupBy, start: Int = 0, rows: Int = 40, byRelevance: Boolean = false): Result {
        val connection = MailIndex(context).ensure()
        val solr = SolrClient(connection)
        val q = text.trim().take(500)
        val p = ArrayList<Pair<String, String>>()

        var smart = false
        val matched: String
        if (q.isEmpty()) {
            matched = "*:*"
        } else {
            // The same parameters as search.opensolr.com, on the mail fields.
            p += "uq" to q
            p += "qf" to QF
            p += "mm" to MM
            p += "df" to "subject_t"
            val lexical = "{!edismax qf=\"$QF\" mm=\"$MM\" v=\$uq}"
            val vector = if (prefs.aiSearch && prefs.vectorAllowed) runCatching { vectorOf(connection.indexName, q) }.getOrNull() else null
            matched = if (vector != null) {
                smart = true
                p += "vectorQuery" to "{!knn f=${MailIndexer.VECTOR} topK=$TOP_K}" + vector.joinToString(",", "[", "]")
                p += "lexicalRaw" to lexical
                val alpha = java.math.BigDecimal(String.format(Locale.US, "%.2f", 1f - prefs.lexicalWeight)).stripTrailingZeros().toPlainString()
                "{!hybrid lexical=\$lexicalRaw vector=\$vectorQuery mode=union alpha=$alpha topN=$TOP_K}"
            } else {
                lexical
            }
        }
        if (prefs.freshSearch) {
            p += "freshBias" to FRESH_BIAS
            p += "matchedQuery" to matched
            p += "q" to "{!boost b=\$freshBias v=\$matchedQuery}"
        } else {
            p += "q" to matched
        }

        // Only the accounts signed in on this phone: another phone's accounts share the index but cannot be opened here.
        val local = store.all()
        if (local.isEmpty()) return Result(emptyList(), emptyList(), 0, false, emptyMap(), emptyMap(), emptyList(), emptyMap())
        p += "fq" to "{!terms f=account_s v=\$f_acc}"
        p += "f_acc" to local.joinToString(",") { MailIndexer.indexKey(it) }
        if (!filters.includeTrash) p += "fq" to "-mailbox_role_ss:(trash OR junk)"
        filters.facets.forEach { (field, values) ->
            if (values.isEmpty() || field !in Filters.FACETS) return@forEach
            val param = "f_" + field
            p += "fq" to "{!terms f=$field tag=$field separator=\u0001 v=\$$param}"
            p += param to values.joinToString("\u0001")
        }
        // The picker gives calendar days as UTC midnights; the range is those days in the phone's own time.
        filters.dates?.let { d -> p += "fq" to "received_dt:[${iso(localMidnight(d.from))} TO ${iso(localMidnight(d.to) + 86_399_999)}]" }
        if (filters.unread) p += "fq" to "seen_b:false"
        if (filters.flagged) p += "fq" to "flagged_b:true"
        if (filters.answered) p += "fq" to "answered_b:true"
        if (filters.attachments) p += "fq" to "has_attachment_b:true"
        if (filters.attachmentText) p += "fq" to "attachment_text_t:[* TO *]"

        // A search with words is ordered by its score, as on search.opensolr.com; recency comes from Fresh.
        // Only a list with no words is ordered newest first.
        val newest = q.isEmpty()
        p += "sort" to if (newest) "received_dt desc, id asc" else "score desc, received_dt desc"
        p += "fl" to "id,score,account_s,email_id_s,thread_id_s,subject_t,from_t,from_s,from_name_s,to_tm,received_dt,preview_t,seen_b,flagged_b,has_attachment_b"
        p += "facet" to "true"
        Filters.FACETS.forEach { f -> p += "facet.field" to "{!ex=$f}$f" }
        p += "facet.field" to "{!ex=from_s key=from_names}from_label_s"
        p += "facet.field" to "{!ex=to_ss key=to_names}to_label_ss"
        p += "facet.limit" to "300"
        p += "facet.mincount" to "1"
        p += "f.year_i.facet.sort" to "index"
        p += "f.weekday_i.facet.sort" to "index"
        if (q.isNotEmpty()) {
            p += "hl" to "true"
            // With vectors the highlighter gets the words with mm=0, so a match found by meaning still shows its words.
            p += "hl.q" to if (smart) "{!edismax qf=\"$QF\" mm=0 v=\$uq}" else q
            p += "hl.fl" to "subject_t,body_t,attachment_text_t"
            p += "hl.method" to "unified"
            p += "hl.defaultSummary" to "true"
            p += "hl.fragsize" to "300"
            p += "hl.snippets" to "1"
            p += "hl.simple.pre" to "<em>"
            p += "hl.simple.post" to "</em>"
            p += "hl.highlightMultiTerm" to "true"
            p += "hl.usePhraseHighlighter" to "true"
            p += "hl.maxAnalyzedChars" to "1000"
            p += "hl.requireFieldMatch" to "false"
            p += "f.subject_t.hl.fragsize" to "0"
            p += "spellcheck" to "true"
            p += "spellcheck.q" to q
            p += "spellcheck.onlyMorePopular" to "false"
            p += "spellcheck.extendedResults" to "false"
            p += "spellcheck.count" to "5"
            p += "spellcheck.collate" to "true"
            p += "spellcheck.collateExtendedResults" to "false"
            p += "spellcheck.maxCollationTries" to "15"
            p += "spellcheck.maxCollations" to "3"
        }
        val field = groupBy.field
        if (field != null) {
            p += "group" to "true"
            p += "group.field" to field
            p += "group.limit" to GROUP_LIMIT.toString()
            p += "group.ngroups" to "true"
            p += "group.sort" to if (newest) "received_dt desc" else "score desc"
            p += "start" to start.coerceIn(0, 5000).toString()
            p += "rows" to GROUP_ROWS.toString()
            if (field == "month_s" || field == "day_s") p += "sort" to "$field desc"
        } else {
            p += "start" to start.coerceIn(0, 5000).toString()
            p += "rows" to rows.coerceIn(1, 100).toString()
        }

        val json = solr.select(p)
        val localKey = local.associate { MailIndexer.indexKey(it) to it.key }
        val hl = json.optJSONObject("highlighting") ?: JSONObject()
        val aiDocs = ArrayList<AiPrompt.Doc>()
        val hlMap = HashMap<String, Map<String, List<String>>>()

        fun parse(docs: JSONArray): List<Hit> = (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("id")
            val h = hl.optJSONObject(id)
            // The fragment with the words found, from the body or an attachment; a plain summary only when neither has them.
            val body = h?.optJSONArray("body_t")?.optString(0).orEmpty()
            val att = h?.optJSONArray("attachment_text_t")?.optString(0).orEmpty()
            val found = listOf(body, att).firstOrNull { "<em>" in it }?.replace(Regex("\\s+"), " ")?.trim()
            // One line in the list: it starts shortly before the first word found, so that word is always in view.
            val snippet = found?.let { f -> val at = f.indexOf("<em>"); if (at > 40) "\u2026" + f.substring(f.lastIndexOf(' ', at - 30).coerceAtLeast(0)).trimStart() else f }
                ?: d.optString("preview_t").ifBlank { body }
            val to = d.optJSONArray("to_tm")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }.orEmpty()
            aiDocs += AiPrompt.Doc(
                id = id, score = if (d.has("score")) d.optDouble("score") else null, title = d.optString("subject_t"),
                description = "From: ${d.optString("from_t")} | To: $to | Date: ${localDate(d.optString("received_dt"))}", text = "",
            )
            if (h != null) hlMap[id] = mapOf(
                "title" to (h.optJSONArray("subject_t")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()),
                "text" to listOfNotNull(h.optJSONArray("body_t"), h.optJSONArray("attachment_text_t")).flatMap { a -> (0 until a.length()).map { a.getString(it) } },
            )
            Hit(
                acc = localKey[d.optString("account_s")] ?: d.optString("account_s"), emailId = d.optString("email_id_s"), threadId = d.optString("thread_id_s"),
                subject = d.optString("subject_t"), from = d.optString("from_name_s").ifBlank { d.optString("from_t") },
                fromEmail = d.optString("from_s"), received = MailSync.parseDate(d.optString("received_dt")), snippet = snippet,
                seen = d.optBoolean("seen_b", true), flagged = d.optBoolean("flagged_b"), hasAttachment = d.optBoolean("has_attachment_b"),
                score = d.optDouble("score", 0.0), docId = id,
            )
        }

        var total = 0L
        val hits = ArrayList<Hit>()
        val groups = ArrayList<Group>()
        if (field != null) {
            val g = json.optJSONObject("grouped")?.optJSONObject(field)
            total = g?.optLong("matches") ?: 0
            val arr = g?.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val one = arr.getJSONObject(i)
                val list = one.getJSONObject("doclist")
                val gh = parse(list.getJSONArray("docs"))
                groups += Group(one.optString("groupValue").takeIf { it != "null" }.orEmpty(), list.optLong("numFound"), gh)
                hits += gh
            }
        } else {
            val response = json.optJSONObject("response") ?: JSONObject()
            total = response.optLong("numFound")
            hits += parse(response.optJSONArray("docs") ?: JSONArray())
        }

        // One line per conversation, its newest match, with the size of the whole conversation.
        val sizes = HashMap<String, Map<String, Int>>()
        hits.groupBy { it.acc }.forEach { (acc, hs) -> sizes[acc] = runCatching { db.threadSizes(acc, hs.map { it.threadId }.filter { it.isNotEmpty() }) }.getOrDefault(emptyMap()) }
        fun byThread(list: List<Hit>): List<Hit> {
            val out = LinkedHashMap<String, Hit>()
            val matches = HashMap<String, Int>()
            list.forEach { h ->
                val k = h.acc + ":" + h.threadId.ifEmpty { h.emailId }
                matches[k] = (matches[k] ?: 0) + 1
                if (!out.containsKey(k)) out[k] = h
            }
            return out.map { (k, h) -> h.copy(threadCount = maxOf(sizes[h.acc]?.get(h.threadId) ?: 1, matches[k] ?: 1)) }
        }
        val threadHits = if (byRelevance) hits else byThread(hits)
        val threadGroups = groups.map { it.copy(hits = byThread(it.hits)) }

        val ff = json.optJSONObject("facet_counts")?.optJSONObject("facet_fields")
        val facets = Filters.FACETS.associateWith { facetList(ff?.optJSONArray(it)) }
        val names = HashMap<String, Pair<String, Int>>()
        (facetList(ff?.optJSONArray("from_names")) + facetList(ff?.optJSONArray("to_names"))).forEach { f ->
            val m = Regex("^(.*) \\(([^()]+@[^()]+)\\)$").find(f.value) ?: return@forEach
            val email = m.groupValues[2]
            val best = names[email]
            if (best == null || f.count > best.second) names[email] = m.groupValues[1] to f.count
        }
        return Result(threadHits, threadGroups, total, smart, facets, names.mapValues { it.value.first }, aiDocs, hlMap)
    }

    /** Streams the AI answer to [question] over the top results of the search that was just run. */
    /**
     * The AI answer from exactly the results the reader sees: [top] are the first rows of the list on
     * screen, in that order. No other search is made.
     */
    suspend fun answer(question: String, top: List<AiPrompt.Doc>, highlights: Map<String, Map<String, List<String>>>, onChunk: (String) -> Unit) {
        if (top.isEmpty()) return
        val connection = MailIndex(context).ensure()
        val bodies = HashMap<String, String>()
        val r = SolrClient(connection).select(
            listOf(
                "q" to "*:*",
                "fq" to "{!terms f=id v=\$ids}",
                "ids" to top.joinToString(",") { it.id },
                "fl" to "id,body_t,attachment_text_t",
                "rows" to top.size.toString(),
            )
        )
        val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        for (i in 0 until docs.length()) docs.getJSONObject(i).let {
            val att = flatten(it.optString("attachment_text_t"))
            bodies[it.optString("id")] = (flatten(freshPart(it.optString("body_t"))) + if (att.isNotEmpty()) "\nAttachment text: $att" else "").trim()
        }
        val ctx = AiPrompt.context(top.map { it.copy(text = bodies[it.id].orEmpty()) }, highlights)
        if (ctx.isEmpty()) return
        api.aiAnswer(connection.indexName, AiPrompt.instruction(ctx, question), onChunk)
    }

    /** The message's own words: quoted history and forwarded originals below it are cut off. */
    private fun freshPart(body: String): String {
        val lines = body.replace("\r", "").lines()
        val out = ArrayList<String>()
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("-----Original Message") || Regex("^On .{4,160} wrote:$").matches(t) || Regex("^(From|De la|Von|De): .+").matches(t) && out.size > 3) break
            if (t.startsWith(">")) continue
            out += line
        }
        return out.joinToString("\n")
    }

    /**
     * Mail bodies arrive as table cells split over lines ("Total de plată:" on one line, "106,35 lei"
     * on the next); the model only binds a value to its label when they sit on one line. Single line
     * breaks become spaces, blank lines stay as paragraph breaks.
     */
    private fun flatten(text: String): String = text.replace("\r", "")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{2,}"), "\u0000")
        .replace("\n", " ")
        .replace('\u0000', '\n')
        .replace(Regex("[ \t\u00A0]{2,}"), " ")
        .replace(Regex("\n\\s*\n+"), "\n")
        .trim()

    private fun localDate(iso: String): String = runCatching {
        java.text.SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(java.util.Date(java.time.Instant.parse(iso).toEpochMilli()))
    }.getOrDefault(iso)

    /** A query is embedded once per app run: typing back to an earlier text costs no AI request. */
    private suspend fun vectorOf(name: String, q: String): FloatArray {
        synchronized(vectors) { vectors[q] }?.let { return it }
        val v = api.embedQuery(name, q)
        synchronized(vectors) { vectors[q] = v }
        return v
    }

    private fun facetList(a: JSONArray?): List<Facet> {
        if (a == null) return emptyList()
        val out = ArrayList<Facet>()
        var i = 0
        while (i + 1 < a.length()) {
            out += Facet(a.optString(i), a.optInt(i + 1))
            i += 2
        }
        return out
    }

    private fun localMidnight(utcMidnight: Long): Long = utcMidnight - TimeZone.getDefault().getOffset(utcMidnight)

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    companion object {
        private val vectors = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 100
        }
        private const val QF = "subject_t^3 from_t to_tm cc_tm attachment_names_tm body_t^2 attachment_text_t^0.01 words_ng^0.01 address_ngk^0.01"
        private const val MM = "2<65% 4<50% 8<40%"
        private const val TOP_K = 790
        private const val GROUP_LIMIT = 5
        private const val GROUP_ROWS = 20

        /** Fresh: 1.0 today, 0.5 at a month, 0.33 at two — a strong pull towards recent mail. */
        private const val FRESH_BIAS = "recip(max(0,ms(NOW,received_dt)),3.86e-10,1,1)"
    }
}
