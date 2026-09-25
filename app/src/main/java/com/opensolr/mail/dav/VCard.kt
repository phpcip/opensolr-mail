package com.opensolr.mail.dav

/** The parts of a vCard (3.0 or 4.0) the contacts show: name, addresses, phones, postal addresses, photo. */
object VCard {

    class Card(val name: String, val emails: List<String>, val phones: List<String>, val addresses: List<String>, val photo: ByteArray?)

    fun parse(text: String): Card? {
        // Folded lines continue with a space or a tab.
        val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        var fn = ""
        var given = ""
        var family = ""
        var org = ""
        val emails = ArrayList<String>()
        val phones = ArrayList<String>()
        val addresses = ArrayList<String>()
        var photo: ByteArray? = null
        for (line in lines) {
            val colon = valueStart(line)
            if (colon < 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val prop = head.substringBefore(';').substringAfterLast('.').uppercase()
            val params = head.substringAfter(';', "").uppercase()
            when (prop) {
                "FN" -> fn = unescape(value).trim()
                "N" -> split(value).let { parts -> family = parts.getOrElse(0) { "" }; given = parts.getOrElse(1) { "" } }
                "ORG" -> org = split(value).firstOrNull().orEmpty()
                "EMAIL" -> unescape(value).trim().removePrefix("mailto:").takeIf { it.contains('@') }?.let { emails += it }
                "TEL" -> unescape(value).trim().removePrefix("tel:").takeIf { it.isNotBlank() }?.let { phones += it }
                "ADR" -> split(value).map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.joinToString(", ").takeIf { it.isNotEmpty() }?.let { addresses += it }
                "PHOTO" -> if (photo == null) photo = photoOf(value, params)
            }
        }
        val name = fn.ifBlank { listOf(given, family).filter { it.isNotBlank() }.joinToString(" ") }.ifBlank { org }
        if (emails.isEmpty() && phones.isEmpty() && name.isBlank()) return null
        return Card(name, emails.distinctBy { it.lowercase() }, phones.distinct(), addresses.distinct(), photo)
    }

    /** Where the value starts: the first colon outside a quoted parameter. */
    private fun valueStart(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    /** Inline pictures only (vCard 3 base64, or a data: address); a picture elsewhere on the web is not fetched. */
    private fun photoOf(value: String, params: String): ByteArray? = runCatching {
        val data = when {
            value.startsWith("data:") -> value.substringAfter(',')
            "ENCODING=B" in params || "ENCODING=BASE64" in params -> value
            else -> return null
        }
        android.util.Base64.decode(data, android.util.Base64.DEFAULT).takeIf { it.size in 1..MAX_PHOTO }
    }.getOrNull()

    /** Components split on unescaped semicolons, each unescaped. */
    private fun split(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) { cur.append(c).append(value[i + 1]); i += 2; continue }
            if (c == ';') { out += unescape(cur.toString()).trim(); cur.clear() } else cur.append(c)
            i++
        }
        out += unescape(cur.toString()).trim()
        return out
    }

    private fun unescape(v: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                out.append(when (val n = v[i + 1]) { 'n', 'N' -> '\n'; else -> n })
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private const val MAX_PHOTO = 256 * 1024
}
