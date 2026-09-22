package com.opensolr.mail.dav

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Just enough iCalendar (RFC 5545) for events: unfolding, properties with parameters, VEVENT and VALARM, and writing them back. */
object Ical {

    data class Prop(val name: String, val params: Map<String, String>, val value: String)

    class Component(val name: String) {
        val props = ArrayList<Prop>()
        val children = ArrayList<Component>()
        fun first(n: String): Prop? = props.firstOrNull { it.name == n }
        fun all(n: String): List<Prop> = props.filter { it.name == n }
    }

    fun parse(text: String): Component? {
        val lines = unfold(text)
        val stack = ArrayDeque<Component>()
        var root: Component? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val p = parseLine(line) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    val c = Component(p.value.uppercase())
                    stack.lastOrNull()?.children?.add(c)
                    if (root == null) root = c
                    stack.addLast(c)
                }
                "END" -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.lastOrNull()?.props?.add(p)
            }
        }
        return root
    }

    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        text.replace("\r\n", "\n").replace("\r", "\n").split("\n").forEach { l ->
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + l.substring(1)
            else out += l
        }
        return out
    }

    private fun parseLine(line: String): Prop? {
        var i = 0
        var inQuote = false
        var colon = -1
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') inQuote = !inQuote
            else if (ch == ':' && !inQuote) { colon = i; break }
            i++
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = splitParams(head)
        val name = parts.first().uppercase()
        val params = HashMap<String, String>()
        parts.drop(1).forEach { p ->
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
        }
        return Prop(name, params, value)
    }

    private fun splitParams(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        s.forEach { ch ->
            when {
                ch == '"' -> { q = !q; sb.append(ch) }
                ch == ';' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out += sb.toString()
        return out
    }

    fun unescape(v: String): String = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    fun escape(v: String): String = v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** A DATE or DATE-TIME property as epoch ms, with whether it is all-day and which zone it was written in. */
    data class Time(val ms: Long, val allDay: Boolean, val zone: String)

    fun time(p: Prop?, fallbackZone: String = TimeZone.getDefault().id): Time? {
        if (p == null) return null
        val v = p.value.trim()
        val isDate = p.params["VALUE"] == "DATE" || v.length == 8
        return runCatching {
            if (isDate) {
                val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                Time(f.parse(v)!!.time, true, "UTC")
            } else if (v.endsWith("Z")) {
                val f = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                Time(f.parse(v)!!.time, false, "UTC")
            } else {
                val zoneId = p.params["TZID"]?.let { z -> zoneOf(z) } ?: fallbackZone
                val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone(zoneId) }
                Time(f.parse(v.take(15))!!.time, false, zoneId)
            }
        }.getOrNull()
    }

    /** An IANA zone for a TZID, which is IANA already at Fastmail; anything unknown falls back to the phone's zone. */
    fun zoneOf(tzid: String): String {
        val clean = tzid.trim().removePrefix("/")
        return if (TimeZone.getAvailableIDs().contains(clean)) clean else TimeZone.getDefault().id
    }

    fun utc(ms: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    fun date(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    fun local(ms: Long, zone: String): String = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }.format(ms)

    /** RFC 5545 duration (e.g. -PT15M, P1D) in ms; null when it is not one. */
    fun duration(v: String): Long? {
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(v.trim()) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        return sign * (g(2) * 7 * 86_400_000 + g(3) * 86_400_000 + g(4) * 3_600_000 + g(5) * 60_000 + g(6) * 1000)
    }

    fun durationOf(ms: Long): String {
        val s = ms / 1000
        return if (s % 86400 == 0L) "P${s / 86400}D" else "PT${s}S"
    }

    /** Folds at 74 octets-ish, as RFC 5545 asks. */
    fun fold(line: String): String {
        if (line.length <= 74) return line
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val end = minOf(line.length, i + if (i == 0) 74 else 73)
            if (i > 0) sb.append("\r\n ")
            sb.append(line, i, end)
            i = end
        }
        return sb.toString()
    }
}
