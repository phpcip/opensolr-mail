package com.opensolr.mail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensolr.mail.ui.theme.LocalPalette

/** File kinds by extension, each with the colour its icon carries. */
object FileKinds {

    private val kinds: List<Pair<Long, Set<String>>> = listOf(
        0xFFD93025 to setOf("pdf"),
        0xFF2B579A to setOf("doc", "docx", "docm", "dot", "dotx", "odt", "ott", "rtf", "pages", "wpd", "hwp"),
        0xFF217346 to setOf("xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "ods", "ots", "csv", "tsv", "numbers"),
        0xFFD24726 to setOf("ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "otp", "key"),
        0xFF5F6368 to setOf("txt", "text", "md", "markdown", "log", "nfo", "tex"),
        0xFF8D6E63 to setOf("zip", "zipx", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "xz", "txz", "zst", "lz", "lzma", "cab", "arj", "z"),
        0xFF0097A7 to setOf("jpg", "jpeg", "jpe", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "svg", "ico", "psd", "ai", "eps", "raw", "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "jxl"),
        0xFFE91E63 to setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma", "amr", "aif", "aiff", "mid", "midi", "3ga"),
        0xFF6A1B9A to setOf("mp4", "m4v", "mov", "avi", "mkv", "webm", "wmv", "flv", "3gp", "3g2", "mpg", "mpeg", "mts", "m2ts", "vob"),
        0xFF455A64 to setOf("html", "htm", "xhtml", "xml", "json", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "php", "py", "java", "kt", "kts", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "swift", "sh", "bash", "ps1", "bat", "sql", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "gradle", "dart", "lua", "pl", "r"),
        0xFFF9A825 to setOf("eml", "msg", "mbox", "emlx", "oft"),
        0xFF1565C0 to setOf("ics", "ical", "ifb", "vcs"),
        0xFF00897B to setOf("vcf", "vcard"),
        0xFF2E7D32 to setOf("apk", "aab", "xapk", "apks"),
        0xFF424242 to setOf("exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "iso", "img", "bin", "jar"),
        0xFF6D4C41 to setOf("epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr"),
        0xFF37474F to setOf("ttf", "otf", "woff", "woff2", "eot"),
        0xFF00695C to setOf("p7s", "p7m", "p7c", "sig", "asc", "gpg", "pgp", "pem", "crt", "cer", "der", "key", "p12", "pfx", "xades", "asice"),
        0xFF5E35B1 to setOf("dwg", "dxf", "stl", "obj", "fbx", "blend", "skp", "step", "stp", "igs", "3mf"),
        0xFF8E24AA to setOf("db", "sqlite", "sqlite3", "mdb", "accdb", "dbf"),
    )

    private val byMime: List<Pair<String, String>> = listOf(
        "application/pdf" to "pdf", "image/" to "img", "audio/" to "aud", "video/" to "vid", "text/calendar" to "ics",
        "text/vcard" to "vcf", "text/x-vcard" to "vcf", "message/rfc822" to "eml", "application/zip" to "zip", "text/" to "txt",
    )

    /** The file's extension, lower case; from the type when the name has none. */
    fun extOf(name: String, type: String): String {
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 10 && it.all { c -> c.isLetterOrDigit() } }
        if (ext != null) return ext
        val t = type.substringBefore(';').trim().lowercase()
        return byMime.firstOrNull { t.startsWith(it.first) }?.second.orEmpty()
    }

    fun colorOf(ext: String): Color {
        val e = ext.lowercase()
        val hit = kinds.firstOrNull { e in it.second }?.first
            ?: when (e) { "img" -> 0xFF0097A7; "aud" -> 0xFFE91E63; "vid" -> 0xFF6A1B9A; else -> 0xFF757575 }
        return Color(hit)
    }
}

/** A page with a folded corner and a band in the colour of its kind, carrying the extension. */
@Composable
fun FileTypeIcon(name: String, type: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val ext = FileKinds.extOf(name, type)
    val color = FileKinds.colorOf(ext)
    val label = ext.uppercase().take(4).ifEmpty { "FILE" }
    Box(modifier.size(width = 21.dp, height = 26.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fold = w * 0.32f
            val stroke = 1.2.dp.toPx()
            val page = Path().apply {
                moveTo(stroke / 2, stroke / 2)
                lineTo(w - fold, stroke / 2)
                lineTo(w - stroke / 2, fold)
                lineTo(w - stroke / 2, h - stroke / 2)
                lineTo(stroke / 2, h - stroke / 2)
                close()
            }
            drawPath(page, p.paper)
            drawPath(page, color, style = Stroke(stroke))
            val corner = Path().apply {
                moveTo(w - fold, stroke / 2)
                lineTo(w - fold, fold)
                lineTo(w - stroke / 2, fold)
            }
            drawPath(corner, color, style = Stroke(stroke))
            drawRect(color, topLeft = Offset(0f, h * 0.52f), size = Size(w, h * 0.34f))
        }
        Text(
            label,
            style = TextStyle(fontSize = if (label.length >= 4) 5.5.sp else 6.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF), lineHeight = 7.sp),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
        )
    }
}

/** A file name cut in the middle when it does not fit, so its end and its extension always show. */
@Composable
fun MiddleEllipsisName(name: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    val keep = minOf(8, base.length / 2)
    val head = base.dropLast(keep)
    val tail = base.takeLast(keep) + ext
    androidx.compose.foundation.layout.Row(modifier) {
        Text(head, style = style, color = color, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(tail, style = style, color = color, maxLines = 1, softWrap = false)
    }
}
