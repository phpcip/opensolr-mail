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
}
