package com.opensolr.mail.jmap

import androidx.core.text.HtmlCompat

object Html {

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Plain text shown as HTML, keeping line breaks. */
    fun fromText(s: String): String = "<div style=\"white-space:pre-wrap;word-wrap:break-word\">" + escape(s) + "</div>"

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
