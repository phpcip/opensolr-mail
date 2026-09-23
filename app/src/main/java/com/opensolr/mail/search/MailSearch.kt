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
        /** "trash" or "junk" when the message lies there, else empty. */
        val bin: String = "",
        /** The message's own Message-ID: the same mail held by two accounts shows once. */
        val messageId: String = "",
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

    /** BEST is the ranked list cut into Best matches and Also similar, as in Opensolr Photos; NONE the plain ranked list. */
    enum class GroupBy(val field: String?) {
        BEST(null), NONE(null), DATE("month_s"), DAY("day_s"), SENDER("from_s"), COMPANY("from_domain_s"), ACCOUNT("account_email_s"),
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
        /** How many messages this page read from the index, before they were folded into conversations: where the next page starts. */
        val fetched: Int = 0,
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
            // +word / -word / +"phrase" / -"phrase": with vectors they leave the text and become filters, so both
            // legs obey them; with nothing left to embed, words only, as typed.
            val ops = SearchOperators.parse(q)
            val embedText = if (ops.hasOps) ops.base else q
            p += "qf" to QF
            p += "mm" to MM
            p += "df" to "subject_t"
            val lexical = "{!edismax qf=\"$QF\" mm=\"$MM\" v=\$uq}"
            val vector = if (prefs.aiSearch && prefs.vectorAllowed && embedText.trim().length >= 2) runCatching { vectorOf(connection.indexName, embedText) }.getOrNull() else null
            if (vector != null && ops.hasOps) {
                p += "uq" to ops.base
                val fields = QF.split(' ').filter { it.isNotBlank() }.joinToString(" ") { it.substringBefore('^') }
                ops.required.forEachIndexed { n, term ->
                    p += "reqQ$n" to term
                    p += "fq" to "{!edismax qf=\"$fields\" mm=\"100%\" v=\$reqQ$n}"
                }
                ops.excluded.forEachIndexed { n, term ->
                    p += "negQ$n" to term
                    p += "fq" to "-{!edismax qf=\"$fields\" mm=\"100%\" v=\$negQ$n}"
                }
            } else {
                p += "uq" to q
            }
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
        // Conversations deleted on this phone stay out even before the index has them in Trash; when Trash is
        // searched, only those deleted for good.
        val hide = com.opensolr.mail.data.MailDb.get(context).hidden()
            .filter { (acc, _, forever) -> forever || !filters.includeTrash }
            .filter { (acc, _, _) -> local.any { it.key == acc } }
            .map { it.second }.distinct()
        if (hide.isNotEmpty()) {
            p += "fq" to "{!bool must=\$hideAll must_not=\$hideQ}"
            p += "hideAll" to "*:*"
            p += "hideQ" to "{!terms f=thread_id_s v=\$hideT}"
            p += "hideT" to hide.joinToString(",")
        }
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
        p += "fl" to "id,score,account_s,email_id_s,thread_id_s,subject_t,from_t,from_s,from_name_s,to_tm,received_dt,preview_t,seen_b,flagged_b,has_attachment_b,mailbox_role_ss,message_id_s"
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
            // No "did you mean" over deliberate operators: the suggestion would drop them.
            if (!SearchOperators.parse(q).hasOps) p += "spellcheck" to "true"
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
                score = d.optDouble("score", 0.0), docId = id, messageId = d.optString("message_id_s"),
                bin = d.optJSONArray("mailbox_role_ss")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.firstOrNull { it == "trash" || it == "junk" }.orEmpty(),
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
        return Result(threadHits, threadGroups, total, smart, facets, names.mapValues { it.value.first }, aiDocs, hlMap, fetched = hits.size)
    }

    /**
     * The AI answer from exactly the results the reader sees: [top] are the first rows of the list on
     * screen, in that order. Those under half the best score stay out; a result that is a conversation
     * goes whole, each message a document of its own with only the words its writer added. No other search is made.
     */
    suspend fun answer(question: String, top: List<AiPrompt.Doc>, highlights: Map<String, Map<String, List<String>>>, onChunk: (String) -> Unit) {
        val best = top.take(AiPrompt.TOP_N).mapNotNull { it.score }.maxOrNull() ?: 0.0
        val chosen = top.take(AiPrompt.TOP_N).filter { best <= 0 || it.score == null || it.score >= best * 0.5 }
        if (chosen.isEmpty()) return
        val connection = MailIndex(context).ensure()
        val solr = SolrClient(connection)
        val r = solr.select(
            listOf(
                "q" to "*:*",
                "fq" to "{!terms f=id v=\$ids}",
                "ids" to chosen.joinToString(",") { it.id },
                "fl" to "id,account_s,email_id_s,thread_id_s,body_t,attachment_text_t",
                "rows" to chosen.size.toString(),
            )
        )
        val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val own = (0 until docs.length()).map { docs.getJSONObject(it) }.associateBy { it.optString("id") }
        // Every message of every conversation among the results, oldest first, in one query.
        val threads = own.values.mapNotNull { d -> d.optString("thread_id_s").takeIf { it.isNotBlank() }?.let { d.optString("account_s") to it } }.distinct()
        val whole = HashMap<Pair<String, String>, List<JSONObject>>()
        if (threads.isNotEmpty()) {
            val t = solr.select(
                listOf(
                    "q" to "*:*",
                    "fq" to "{!terms f=thread_id_s v=\$tids}",
                    "tids" to threads.joinToString(",") { it.second },
                    "fq" to "{!terms f=account_s v=\$accs}",
                    "accs" to threads.map { it.first }.distinct().joinToString(","),
                    "fl" to "id,account_s,email_id_s,thread_id_s,subject_t,from_t,to_tm,received_dt,body_t,attachment_text_t",
                    "sort" to "received_dt asc, id asc",
                    "rows" to "300",
                )
            )
            val all = t.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
            (0 until all.length()).map { all.getJSONObject(it) }
                .groupBy { it.optString("account_s") to it.optString("thread_id_s") }
                .forEach { (k, v) -> whole[k] = v }
        }
        val localKey = store.all().associate { MailIndexer.indexKey(it) to it.key }
        // The bodies this phone holds, one read per account: their HTML shows where each quote starts.
        val held = HashMap<String, com.opensolr.mail.data.Message>()
        (own.values + whole.values.flatten()).groupBy { localKey[it.optString("account_s")] }.forEach { (acc, ms) ->
            if (acc == null) return@forEach
            runCatching { db.messages(acc, ms.map { it.optString("email_id_s") }.distinct()) }.getOrDefault(emptyList()).forEach { held[acc + ":" + it.id] = it }
        }
        fun heldOf(m: JSONObject) = localKey[m.optString("account_s")]?.let { held[it + ":" + m.optString("email_id_s")] }
        val out = ArrayList<AiPrompt.Doc>()
        val used = HashSet<String>()
        var left = AI_WORDS
        fun add(doc: AiPrompt.Doc) {
            if (left <= 0 || !used.add(doc.id)) return
            val text = cutWords(doc.text, minOf(AI_DOC_WORDS, left))
            left -= words(text)
            out += doc.copy(text = text, score = null)
        }
        for (d in chosen) {
            val hit = own[d.id]
            val conversation = hit?.let { whole[it.optString("account_s") to it.optString("thread_id_s")] }.orEmpty()
            if (hit == null) continue
            if (conversation.size <= 1) {
                add(d.copy(text = messageText(hit, heldOf(hit), emptyList())))
                continue
            }
            // The conversation in its order; each message loses the lines it repeats from those before it.
            val earlier = ArrayList<String>()
            conversation.forEach { m ->
                val to = m.optJSONArray("to_tm")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }.orEmpty()
                val id = m.optString("id")
                val text = messageText(m, heldOf(m), earlier)
                earlier += m.optString("body_t")
                // A message that only repeats what came before adds nothing to read.
                if (text.isBlank() && id != d.id) return@forEach
                add(
                    AiPrompt.Doc(
                        id = id, score = null, title = m.optString("subject_t").ifBlank { d.title },
                        description = "From: ${m.optString("from_t")} | To: $to | Date: ${localDate(m.optString("received_dt"))}",
                        text = text,
                    )
                )
            }
        }
        val ctx = AiPrompt.context(out, highlights, topN = out.size, maxWords = AI_DOC_WORDS)
        if (ctx.isEmpty()) return
        api.aiAnswer(connection.indexName, AiPrompt.instruction(ctx, question), onChunk)
    }

    /**
     * What the writer of one message added: the quote cut where the mail program marked it (from the
     * HTML this phone holds), else at an "On ... wrote:" / Original Message / Outlook header line, then
     * every line already written in [earlier] messages of the conversation dropped.
     */
    private fun messageText(d: JSONObject, local: com.opensolr.mail.data.Message?, earlier: List<String>): String {
        val html = local?.bodyHtml.orEmpty()
        val fresh = if (html.isNotBlank()) freshPart(com.opensolr.mail.jmap.Html.withoutQuotes(html))
        else freshPart(local?.bodyText?.takeIf { it.isNotBlank() } ?: d.optString("body_t"))
        val body = flatten(notRepeated(fresh, earlier))
        val att = flatten(d.optString("attachment_text_t"))
        return (body + if (att.isNotEmpty()) "\nAttachment text: $att" else "").trim()
    }

    /** Lines of [text] already present in an earlier message are a quote that escaped the marks: they go. */
    private fun notRepeated(text: String, earlier: List<String>): String {
        if (earlier.isEmpty()) return text
        fun norm(l: String) = l.trim().trimStart('>', ' ').replace(SPACES, " ").lowercase()
        val seen = HashSet<String>()
        earlier.forEach { e -> e.lines().forEach { l -> val n = norm(l); if (n.length >= MIN_REPEAT_CHARS) seen += n } }
        return text.lines().filterNot { l -> norm(l).let { it.length >= MIN_REPEAT_CHARS && it in seen } }.joinToString("\n")
    }

    private fun words(text: String): Int = WORD.findAll(text).count()

    private fun cutWords(text: String, max: Int): String {
        if (max <= 0) return ""
        val m = WORD.findAll(text).elementAtOrNull(max - 1) ?: return text
        return text.substring(0, m.range.last + 1)
    }

    /** The message's own words: quoted history below it is cut off, a forwarded original stays. */
    private fun freshPart(body: String): String {
        val lines = body.replace("\r", "").lines()
        val out = ArrayList<String>()
        var written = false
        for ((i, line) in lines.withIndex()) {
            val t = line.trim()
            if (FORWARD_LINE.containsMatchIn(t)) return (out + lines.drop(i)).joinToString("\n").trim()
            val outlook = OUTLOOK_FROM.matches(t) && lines.subList(i + 1, minOf(lines.size, i + 5)).any { OUTLOOK_SENT.matches(it.trim()) }
            if (written && (QUOTE_HEAD.matches(t) || outlook)) break
            if (t.startsWith(">")) continue
            if (t.isNotEmpty()) written = true
            out += line
        }
        return out.joinToString("\n").trim()
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
        /** Words one message may give the AI answer, and all of them together: what fits the model with room to answer. */
        private const val AI_DOC_WORDS = 10_000
        private const val AI_WORDS = 20_000
        /** A repeated line this long is a quote; shorter ones ("Thanks,", "Hi John") can be written again. */
        private const val MIN_REPEAT_CHARS = 16
        private val FORWARD_LINE = Regex("(?i)-{2,}\\s*Forwarded message|^Begin forwarded message")
        private val OUTLOOK_FROM = Regex("^(From|De la|Von|De|Van|Da):\\s.+")
        private val OUTLOOK_SENT = Regex("^(Sent|Date|Trimis|Gesendet|Envoy\u00e9|Data|Verzonden):\\s.+")
        private val WORD = Regex("\\S+")
        private val SPACES = Regex("\\s+")
        private val QUOTE_HEAD = Regex("(?i)^(On .{4,200}wrote:|Le .{4,200}a \u00e9crit\\s?:|Am .{4,200}schrieb .{1,120}:|\u00cen .{4,200}a scris:|-{2,}\\s*(Original Message|Mesaj original|Urspr\u00fcngliche Nachricht)\\s*-{2,}|_{10,})$")

        /** Fresh: 1.0 today, 0.5 at a month, 0.33 at two — a strong pull towards recent mail. */
        private const val FRESH_BIAS = "recip(max(0,ms(NOW,received_dt)),3.86e-10,1,1)"
    }
}
