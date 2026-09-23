package com.opensolr.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opensolr.mail.ui.theme.LocalPalette

private val Corner = RoundedCornerShape(2.dp)

/** One block of the answer: what a line or a run of lines stands for. */
private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val depth: Int, val mark: String?, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
    data object Gap : Block
}

private val BULLET = Regex("^(\\s*)[-*+•]\\s+(.*)$")
private val NUMBER = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

/** The Markdown the AI answer is written in: headings, lists at any depth, quotes, code, tables, rules, links. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val blocks = remember(text) { parse(text) }
    val body = MaterialTheme.typography.bodyMedium
    Column(modifier) {
        blocks.forEach { b ->
            when (b) {
                is Block.Gap -> Spacer(Modifier.height(6.dp))
                is Block.Para -> Text(inline(b.text, p.accent, p.band), style = body, color = p.ink)
                is Block.Heading -> Text(
                    inline(b.text, p.accent, p.band),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    color = p.ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                is Block.Item -> Row(Modifier.padding(start = (4 + 16 * b.depth).dp, top = 2.dp)) {
                    if (b.mark == null) Text(if (b.depth == 0) "•" else "◦", style = body, color = p.accent, modifier = Modifier.width(14.dp))
                    else Text(b.mark, style = body, color = p.accent, modifier = Modifier.width(24.dp))
                    Text(inline(b.text, p.accent, p.band), style = body, color = p.ink)
                }
                is Block.Quote -> Row(Modifier.padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(p.accent))
                    Text(inline(b.text, p.accent, p.band), style = body, color = p.muted, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 10.dp))
                }
                is Block.Code -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).background(p.paper, Corner).border(1.dp, p.hairline, Corner)
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                ) { Text(b.text, style = body, fontFamily = FontFamily.Monospace, color = p.ink, softWrap = false) }
                is Block.Table -> MdTable(b.rows)
                is Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(p.hairline))
            }
        }
    }
}

/** A table on a plain grid; columns sized by their longest cell, wider than the screen scrolls sideways. */
@Composable
private fun MdTable(rows: List<List<String>>) {
    val p = LocalPalette.current
    val cols = rows.maxOf { it.size }
    val widths = (0 until cols).map { c -> (rows.maxOf { it.getOrNull(c)?.length ?: 0 } * 8).coerceIn(56, 260).dp }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, p.hairline, Corner)) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) p.band else Color.Transparent).height(IntrinsicSize.Min)) {
                    (0 until cols).forEach { c ->
                        Text(
                            inline(row.getOrNull(c).orEmpty(), p.accent, p.band), style = MaterialTheme.typography.bodySmall, color = p.ink,
                            fontWeight = if (r == 0) FontWeight.Bold else null,
                            modifier = Modifier.width(widths[c]).fillMaxHeight().border(0.5.dp, p.hairline).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun parse(text: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = text.replace("\r", "").lines()
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotEmpty()) { out += Block.Para(para.toString()); para.clear() } }
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val t = line.trim()
        when {
            t.startsWith("```") || t.startsWith("~~~") -> {
                flush()
                val fence = t.take(3)
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) { code += lines[i].trimEnd(); i++ }
                out += Block.Code(code.joinToString("\n"))
            }
            t.isEmpty() -> { flush(); if (out.lastOrNull() !is Block.Gap && out.isNotEmpty()) out += Block.Gap }
            t.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                flush()
                val rows = ArrayList<List<String>>()
                rows += cells(t)
                i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                out += Block.Table(rows)
                continue
            }
            RULE.matches(line) -> { flush(); out += Block.Rule }
            HEADING.matches(t) -> { flush(); HEADING.find(t)!!.let { out += Block.Heading(it.groupValues[1].length, it.groupValues[2]) } }
            t.startsWith(">") -> {
                flush()
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) { q += lines[i].trim().removePrefix(">").trim(); i++ }
                out += Block.Quote(q.joinToString("\n"))
                continue
            }
            BULLET.matches(line) -> { flush(); BULLET.find(line)!!.let { out += Block.Item(depth(it.groupValues[1]), null, it.groupValues[2]) } }
            NUMBER.matches(line) -> { flush(); NUMBER.find(line)!!.let { out += Block.Item(depth(it.groupValues[1]), it.groupValues[2] + ".", it.groupValues[3]) } }
            // A line under a list item, indented, carries on that item.
            line.startsWith("  ") && out.lastOrNull() is Block.Item && para.isEmpty() -> {
                val last = out.removeAt(out.size - 1) as Block.Item
                out += last.copy(text = last.text + " " + t)
            }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
        }
        i++
    }
    flush()
    while (out.lastOrNull() is Block.Gap) out.removeAt(out.size - 1)
    return out
}

private fun depth(indent: String): Int = (indent.replace("\t", "    ").length / 2).coerceAtMost(4)

private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private val WEB_URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
private val BARE_URL = Regex("https?://[^\\s<>()\\[\\]\"']+[^\\s<>()\\[\\]\"'.,;:!?]")

/** Only web links open: any other scheme stays plain text. */
private fun safeUrl(url: String): String? {
    val u = url.trim()
    return if (WEB_URL.matches(u)) u else null
}

/** Bold, italic, strike, inline code, [links](url) and bare web addresses; a backslash keeps the next mark literal. */
private fun inline(s: String, link: Color, codeFill: Color): AnnotatedString = buildAnnotatedString { appendInline(s, link, codeFill) }

private fun AnnotatedString.Builder.appendInline(s: String, link: Color, codeFill: Color) {
    val linkStyle = TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline))
    var i = 0
    val plain = StringBuilder()
    fun flushPlain() {
        if (plain.isEmpty()) return
        // Bare addresses inside plain text become links too.
        val text = plain.toString()
        var at = 0
        BARE_URL.findAll(text).forEach { m ->
            append(text.substring(at, m.range.first))
            withLink(LinkAnnotation.Url(m.value, linkStyle)) { append(m.value) }
            at = m.range.last + 1
        }
        append(text.substring(at))
        plain.clear()
    }
    fun closing(mark: String, from: Int): Int {
        val end = s.indexOf(mark, from)
        return if (end > from) end else -1
    }
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_[]()~#>|-" -> { plain.append(s[i + 1]); i += 2 }
            c == '`' -> {
                val end = closing("`", i + 1)
                if (end < 0) { plain.append(c); i++ } else {
                    flushPlain()
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeFill)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val mark = s.substring(i, i + 2)
                val end = closing(mark, i + 2)
                if (end < 0) { plain.append(mark); i += 2 } else {
                    flushPlain()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(s.substring(i + 2, end), link, codeFill) }
                    i = end + 2
                }
            }
            s.startsWith("~~", i) -> {
                val end = closing("~~", i + 2)
                if (end < 0) { plain.append("~~"); i += 2 } else {
                    flushPlain()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(s.substring(i + 2, end), link, codeFill) }
                    i = end + 2
                }
            }
            (c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && (i == 0 || !s[i - 1].isLetterOrDigit() || c == '*') -> {
                val end = closing(c.toString(), i + 1)
                if (end < 0 || s[end - 1].isWhitespace()) { plain.append(c); i++ } else {
                    flushPlain()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, end), link, codeFill) }
                    i = end + 1
                }
            }
            c == '[' -> {
                val close = s.indexOf(']', i + 1)
                val url = if (close > 0 && close + 1 < s.length && s[close + 1] == '(') s.indexOf(')', close + 2).takeIf { it > 0 }?.let { s.substring(close + 2, it) to it } else null
                val safe = url?.let { safeUrl(it.first) }
                if (url == null) { plain.append(c); i++ } else {
                    flushPlain()
                    val label = s.substring(i + 1, close)
                    if (safe != null) withLink(LinkAnnotation.Url(safe, linkStyle)) { appendInline(label, link, codeFill) }
                    else appendInline(label, link, codeFill)
                    i = url.second + 1
                }
            }
            else -> { plain.append(c); i++ }
        }
    }
    flushPlain()
}
