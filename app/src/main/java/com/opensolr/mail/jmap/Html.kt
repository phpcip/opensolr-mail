package com.opensolr.mail.jmap

import androidx.core.text.HtmlCompat

object Html {

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Plain text shown as HTML, keeping line breaks. */
    fun fromText(s: String): String = "<div style=\"white-space:pre-wrap;word-wrap:break-word\">" + escape(s) + "</div>"

    private val TAGS = Regex("(?i)<\\s*(/?\\s*(html|head|body|div|p|br|span|table|tbody|thead|tr|td|th|a|img|font|b|i|u|em|strong|ul|ol|li|h[1-6]|center|blockquote|pre|hr|meta|style|script|o:p)\\b[^>]*|!--)")
    private val ENTITY = Regex("(?i)&(#\\d{1,7}|#x[0-9a-f]{1,6}|[a-z][a-z0-9]{1,31});")
    private val INVISIBLE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00AD\\u200B-\\u200D\\u2060\\uFEFF\\uFFFC]")

    /**
     * Any text as pure, human readable plain text: markup that slipped into a text part is rendered
     * to its words, entities become their characters, invisible characters go. Nothing is shortened.
     */
    fun plain(s: String): String {
        var t = s.replace("\r\n", "\n").replace('\r', '\n')
        if (TAGS.containsMatchIn(t)) t = toText(t)
        t = ENTITY.replace(t) { m -> HtmlCompat.fromHtml(m.value, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().ifEmpty { m.value } }
        return t.replace(INVISIBLE, "").replace('\u00A0', ' ').trim()
    }

    /** The words of an HTML body, for the index and for replies. */
    fun toText(html: String): String {
        val noStyle = html.replace(Regex("(?is)<(style|script|head)[^>]*>.*?</\\1>"), " ")
        return HtmlCompat.fromHtml(noStyle, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
            .replace('\u00A0', ' ')
            .replace('\uFFFC', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private val QUOTE_SELECTOR = listOf(
        "div.gmail_quote", "div.gmail_quote_container", "blockquote.gmail_quote", "blockquote[type=cite]",
        "div#divRplyFwdMsg", "div#appendonsend", "div.moz-cite-prefix", "div#qt", "div.yahoo_quoted", "div.OutlookMessageHeader",
    ).joinToString(", ")
    private val FORWARDED = Regex("(?i)-{2,}\\s*Forwarded message|Begin forwarded message|Mesaj redirec")
    private val WROTE = Regex("(?i)^(On .{4,200}wrote:|-{2,}\\s*Original Message\\s*-{2,}|-{2,}\\s*Forwarded message\\s*-{2,})$")
    private val QUOTE_LINE = Regex("(?im)^\\s*(On .{4,200}wrote:|-{2,}\\s*Original Message\\s*-{2,}|-{2,}\\s*Forwarded message\\s*-{2,}|From:\\s.+)\\s*$")

    /**
     * The message as written, with the quoted history below it folded under [label]: a click on the
     * label opens it. Found by the marks mail programs put on a quote (Gmail, Apple Mail, Outlook,
     * Fastmail, Thunderbird, Yahoo) or by an "On ... wrote:" / "Original Message" line. A message that is
     * nothing but a quote (a forward) is shown whole.
     */
    fun foldQuotes(html: String, label: String, hide: String): String = runCatching {
        val doc = org.jsoup.Jsoup.parseBodyFragment(html)
        val body = doc.body()
        val marked = body.select(QUOTE_SELECTOR).firstOrNull()
        // A written "On ... wrote:" or "Original Message" line, or Outlook's short header block (From: with Sent: or Date:).
        val byLine = body.getAllElements().firstOrNull { e ->
            if (e === body) return@firstOrNull false
            val own = e.ownText().trim()
            val all = e.text().trim()
            (own.isNotEmpty() && WROTE.containsMatchIn(own)) ||
                (all.length < 600 && all.startsWith("From:") && (all.contains("Sent:") || all.contains("Date:")))
        }
        val start = listOfNotNull(marked, byLine).minByOrNull { body.getAllElements().indexOf(it) } ?: return@runCatching html
        // Before the quote there must be something of the message itself, or there is nothing to fold.
        val before = StringBuilder()
        run {
            for (n in body.getAllElements()) {
                if (n === start) break
                if (n !== body && n.parents().none { it === start }) before.append(n.ownText())
            }
        }
        if (before.toString().isBlank()) return@runCatching html
        val details = org.jsoup.nodes.Element("details").addClass("osq")
        details.appendElement("summary").appendElement("span").addClass("s").text(label).parent()!!.appendElement("span").addClass("h").text(hide)
        start.before(details)
        val move = ArrayList<org.jsoup.nodes.Node>()
        var n: org.jsoup.nodes.Node? = start
        while (n != null) { move += n; n = n.nextSibling() }
        var parent = details.parent()
        while (parent != null && parent !== body) {
            var s2 = parent.nextSibling()
            while (s2 != null) { move += s2; s2 = s2.nextSibling() }
            parent = parent.parent()
        }
        move.forEach { details.appendChild(it) }
        body.html()
    }.getOrDefault(html)

    /** The message's own words as plain text: the quote found by [foldQuotes] is dropped; a forward stays whole. */
    fun withoutQuotes(html: String): String = runCatching {
        val body = org.jsoup.Jsoup.parseBodyFragment(foldQuotes(html, "", "")).body()
        // A forward's original is what was sent along, not a quote of this conversation: it stays.
        if (body.select("details.osq").text().take(300).contains(FORWARDED)) return@runCatching plain(html)
        body.select("details.osq").remove()
        plain(body.html())
    }.getOrDefault(plain(html))

    /** Plain text shown as HTML, the quoted history below the first quote line folded under [label]. */
    fun fromTextFolded(text: String, label: String, hide: String): String {
        val lines = text.lines()
        val at = lines.indexOfFirst { l -> QUOTE_LINE.matches(l) || l.trimStart().startsWith(">") }
        if (at <= 0 || lines.take(at).all { it.isBlank() }) return fromText(text)
        val head = lines.take(at).joinToString("\n").trimEnd()
        val tail = lines.drop(at).joinToString("\n")
        return fromText(head) + "<details class=\"osq\"><summary><span class=\"s\">" + escape(label) + "</span><span class=\"h\">" + escape(hide) + "</span></summary>" + fromText(tail) + "</details>"
    }
}
