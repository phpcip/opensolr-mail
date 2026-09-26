package com.opensolr.mail.search

/** The prompt of the AI answer in Opensolr Mail: the Opensolr AI Hints prompt, with a drill-down in place of "find all". */
object AiPrompt {

    const val TOP_N = 5
    const val MAX_WORDS = 1500

    data class Doc(val id: String, val score: Double?, val title: String, val description: String, val text: String, val textT: String = "")

    fun context(docs: List<Doc>, hl: Map<String, Map<String, List<String>>>, topN: Int = TOP_N, maxWords: Int = MAX_WORDS): String {
        val sb = StringBuilder()
        var top = 0.0
        for (i in 0 until topN) docs.getOrNull(i)?.score?.let { top = maxOf(top, it) }
        for (i in 0 until topN) {
            val d = docs.getOrNull(i) ?: continue
            if (top > 0 && d.score != null && d.score < top * 0.5) continue
            val n = sb.split("===== DOCUMENT ").size
            sb.append("===== DOCUMENT ").append(n).append(" =====\n")
            sb.append(d.title).append("\n")
            sb.append(d.description).append("\n")
            val docHl = hl[d.id].orEmpty()
            val snippets = ArrayList<String>()
            for (field in listOf("title", "description", "text")) {
                docHl[field].orEmpty().forEach { s ->
                    val m = markFragment(stripTags(s))
                    if (m.isNotEmpty()) snippets += m
                }
            }
            if (snippets.isNotEmpty()) {
                sb.append("MOST RELEVANT EXCERPTS:\n")
                snippets.forEach { sb.append("- ").append(it).append("\n") }
                sb.append("\n")
            }
            val textT = d.textT.trim()
            val body = if (textT.toByteArray(Charsets.UTF_8).size > 50) textT + "\n" + d.text else d.text
            sb.append(excerpt(body, maxWords)).append("\n")
            sb.append("===== END OF DOCUMENT ").append(n).append(" =====\n\n")
        }
        return sb.toString()
    }

    /** [extra] are the reader's own instructions, placed right before the question; none leaves the prompt as canonical. */
    fun instruction(context: String, query: String, extra: String = ""): String {
        val count = maxOf(1, Regex("===== DOCUMENT ").findAll(context).count())
        return context + "\n\n" +
            "Those were the " + count + " documents.\n\n" +
            "Now answer the question below using only facts stated in those documents. " +
            "First drill down: discard every document that does not mention what the question " +
            "asks about, and when the question names a day, a month or a year, also every " +
            "document not dated within it. Answer from the documents that are left, and from " +
            "those alone. Where more than one of them bears on the question, combine what each " +
            "one adds into a single answer.\n" +
            "Write the answer itself, formatted in Markdown for reading, and be as succinct as " +
            "possible. Begin with one short sentence that answers the question directly. Where " +
            "there are several items, follow it with a short Markdown bullet list, one line per " +
            "item, each opening with a bold lead-in that names it. If there is just one thing to " +
            "say, that one sentence is the whole answer. Give only the details that answer the question — the people, " +
            "places, numbers, dates and named events involved — and leave out everything else. " +
            "Never repeat an item. Never invent generic headings such as \"Overview\", \"Key Points\" or " +
            "\"Summary\". Never begin with \"Based on\" or \"According to\", and never end with " +
            "a sentence about the documents or the context. Do not name documents, do not say " +
            "which ones you used, and do not comment on the ones you did not use.\n" +
            "Only if not one of the " + count + " documents is about the question, reply with " +
            "a single sentence that starts \"There is no information about\" and then names " +
            "what they cover instead.\n\n" +
            (if (extra.isNotBlank()) "Additional instructions: " + extra.trim() + "\n\n" else "") +
            "Question: " + query + "\n" +
            "Answer:"
    }

    /**
     * The Mail answer as picks: the model replies in JSON (held to it by the server) with one sentence and, per
     * document that answers the question, its number and the key fact it gives. The app shows each document's own
     * date and subject next to the fact, so no date is ever retyped by the model.
     */
    fun picksInstruction(context: String, query: String, extra: String = ""): String {
        val count = maxOf(1, Regex("===== DOCUMENT ").findAll(context).count())
        return context + "\n\n" +
            "Those were the " + count + " documents.\n\n" +
            "Now answer the question below using only facts stated in those documents. " +
            "First drill down: discard every document that does not mention what the question " +
            "asks about, and when the question names a day, a month or a year, also every " +
            "document not dated within it. Answer from the documents that are left, and from " +
            "those alone.\n" +
            "Reply in JSON. \"answer\": two to four sentences that answer the question directly and " +
            "sum up what those documents say about it, in the language of the question. \"items\": one " +
            "entry for each document that is left, never the same document twice, with \"doc\": its " +
            "number and \"fact\": one short sentence with what it gives for the question (amounts, " +
            "numbers, names, what happened), in the language of the question. If not one " +
            "document is about the question, \"answer\" is an empty string and \"items\" is empty.\n\n" +
            (if (extra.isNotBlank()) "Additional instructions: " + extra.trim() + "\n\n" else "") +
            "Question: " + query + "\n" +
            "Answer:"
    }

    private fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), "")

    private fun markFragment(raw: String): String {
        var f = raw.replace(Regex("\\s+"), " ").trim()
        if (f.isEmpty()) return ""
        if (!Regex("^[\"'(\\[]?[\\p{Lu}\\p{N}]").containsMatchIn(f)) f = "... $f"
        if (!Regex("[.!?\\u2026][\"')\\]]?$").containsMatchIn(f)) f += " ..."
        return f
    }

    private fun excerpt(text: String, maxWords: Int): String {
        if (text.isEmpty() || maxWords <= 0) return ""
        val words = Regex("\\S+").findAll(text).toList()
        if (words.size <= maxWords) return text
        val last = words[maxWords - 1]
        return text.substring(0, last.range.last + 1)
    }
}
