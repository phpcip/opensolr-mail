package com.opensolr.mail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opensolr.mail.ui.theme.LocalPalette

/** The Markdown the AI answer is written in: paragraphs, lists, headings and bold. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(modifier) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Text("", style = MaterialTheme.typography.bodySmall)
                Regex("^\\s*[-*•]\\s+").containsMatchIn(line) -> Row(Modifier.padding(start = 4.dp, top = 2.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = p.accent, modifier = Modifier.width(14.dp))
                    Text(inline(line.replaceFirst(Regex("^\\s*[-*•]\\s+"), "")), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                }
                Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(line) -> Row(Modifier.padding(start = 4.dp, top = 2.dp)) {
                    val n = Regex("^\\s*(\\d+)").find(line)!!.groupValues[1]
                    Text("$n.", style = MaterialTheme.typography.bodyMedium, color = p.accent, modifier = Modifier.width(22.dp))
                    Text(inline(line.replaceFirst(Regex("^\\s*\\d+[.)]\\s+"), "")), style = MaterialTheme.typography.bodyMedium, color = p.ink)
                }
                line.startsWith("#") -> Text(inline(line.trimStart('#').trim()), style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.padding(top = 6.dp))
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val start = s.indexOf("**", i)
        if (start < 0) { append(s.substring(i)); break }
        val end = s.indexOf("**", start + 2)
        if (end < 0) { append(s.substring(i)); break }
        append(s.substring(i, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(start + 2, end)) }
        i = end + 2
    }
}
