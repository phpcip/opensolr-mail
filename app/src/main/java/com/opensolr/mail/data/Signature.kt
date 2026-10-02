package com.opensolr.mail.data

import com.opensolr.mail.jmap.Html
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/** The one signature of the app, the same under every message sent from every account: kept as HTML, sent as HTML and as text. */
object Signature {

    private val SAFE: Safelist = Safelist.none()
        .addTags("b", "strong", "i", "em", "u", "a", "br", "div", "p")
        .addAttributes("a", "href")
        .addProtocols("a", "href", "http", "https", "mailto", "tel")

    /** Only bold, italic, underline, links and line breaks survive; anything else is dropped to its words. */
    fun clean(html: String): String {
        val out = Jsoup.clean(html, "", SAFE, org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
        return if (text(out).isBlank()) "" else out
    }

    /** The signature as plain text: lines kept, a link written as its words followed by its address. */
    fun text(html: String): String {
        if (html.isBlank()) return ""
        val sb = StringBuilder()
        fun walk(n: Node) {
            when (n) {
                is TextNode -> sb.append(n.wholeText.replace('\u00A0', ' '))
                is Element -> {
                    val block = n.normalName() in setOf("div", "p")
                    if (block && sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    if (n.normalName() == "br") { sb.append('\n'); return }
                    n.childNodes().forEach { walk(it) }
                    if (n.normalName() == "a") {
                        val href = n.attr("href").removePrefix("mailto:").removePrefix("tel:")
                        if (href.isNotBlank() && href != n.text().trim()) sb.append(" <").append(href).append('>')
                    }
                    if (block && !sb.endsWith("\n")) sb.append('\n')
                }
            }
        }
        Jsoup.parseBodyFragment(html).body().childNodes().forEach { walk(it) }
        return sb.toString().lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** What goes under the words of a text part, the usual delimiter first. */
    fun textBlock(html: String): String = text(html).takeIf { it.isNotEmpty() }?.let { "\n\n-- \n$it" }.orEmpty()

    /** The same, for the HTML part. */
    fun htmlBlock(html: String): String = if (html.isBlank()) "" else "<div><br>-- <br>$html</div>"

    /** A message as text and as HTML: what was written, the signature, then the quoted message as it was. */
    fun compose(typed: String, signatureHtml: String, quote: String): Pair<String, String> {
        val words = typed.trimEnd()
        val q = quote.trim('\n')
        val text = words + textBlock(signatureHtml) + (if (q.isNotBlank()) "\n\n$q" else "")
        val html = Html.fromText(words) + htmlBlock(signatureHtml) + (if (q.isNotBlank()) "<br>" + Html.fromText(q) else "")
        return text to html
    }

    /** A draft written with the signature, split back into what was written and the quote under it; null when the signature is not in it. */
    fun split(body: String, signatureHtml: String): Pair<String, String>? {
        val sig = text(signatureHtml).takeIf { it.isNotEmpty() } ?: return null
        val b = body.replace("\r\n", "\n")
        val mark = "-- \n$sig"
        val at = b.indexOf(mark).takeIf { it >= 0 } ?: return null
        return b.substring(0, at).trimEnd() to b.substring(at + mark.length).trim('\n')
    }
}
