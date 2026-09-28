package com.opensolr.mail.search

import java.time.LocalDate

/**
 * Words of time in a search ("last month", "2 years ago", "ieri") read as the stretch of days they mean, counted
 * from today on the phone's clock, and taken out of the text: the search keeps only messages from those days, and
 * the words and the meaning look for the rest. The seven languages of the app.
 */
object TimeWords {

    /** Days from [from] up to, not including, [until], in the phone's own calendar. */
    data class Span(val from: LocalDate, val until: LocalDate)

    /** The text without its words of time, and the days they meant. */
    data class Parsed(val text: String, val span: Span)

    private fun day(d: LocalDate) = Span(d, d.plusDays(1))
    private fun week(d: LocalDate) = d.with(java.time.DayOfWeek.MONDAY).let { Span(it, it.plusWeeks(1)) }
    private fun thisWeek(d: LocalDate) = Span(d.with(java.time.DayOfWeek.MONDAY), d.plusDays(1))
    private fun month(d: LocalDate) = d.withDayOfMonth(1).let { Span(it, it.plusMonths(1)) }
    private fun year(d: LocalDate) = d.withDayOfYear(1).let { Span(it, it.plusYears(1)) }
    /** From [from] up to and including today. */
    private fun untilNow(from: LocalDate, today: LocalDate) = Span(from, today.plusDays(1))

    /** "the last N units", "past N units": the stretch ending today. */
    private fun last(n: Int, unit: Unit, today: LocalDate): Span = when (unit) {
        Unit.DAY -> untilNow(today.minusDays(n.toLong() - 1), today)
        Unit.WEEK -> untilNow(today.minusWeeks(n.toLong()).plusDays(1), today)
        Unit.MONTH -> untilNow(today.minusMonths(n.toLong()).plusDays(1), today)
        Unit.YEAR -> untilNow(today.minusYears(n.toLong()).plusDays(1), today)
    }

    private val MONTHS: Map<String, Int> = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7, "august" to 8,
        "september" to 9, "october" to 10, "november" to 11, "december" to 12,
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        "ianuarie" to 1, "februarie" to 2, "martie" to 3, "aprilie" to 4, "mai" to 5, "iunie" to 6, "iulie" to 7, "septembrie" to 9, "octombrie" to 10, "noiembrie" to 11, "decembrie" to 12,
        "januar" to 1, "februar" to 2, "märz" to 3, "maerz" to 3, "juni" to 6, "juli" to 7, "oktober" to 10, "dezember" to 12,
        "janvier" to 1, "février" to 2, "fevrier" to 2, "mars" to 3, "avril" to 4, "juin" to 6, "juillet" to 7, "août" to 8, "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "décembre" to 12,
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7, "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12,
    )
    private val MONTH_NAMES = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

    /** A month by name, with its year, or the latest one of that name that has already begun. */
    private fun namedMonth(name: String, year: String?, today: LocalDate): Span? {
        val m = MONTHS[name.lowercase()] ?: return null
        // "may" and "mai" are everyday words too: a month only with a year after them.
        if (year == null && name.lowercase() in setOf("may", "mai", "mars")) return null
        val y = year?.toIntOrNull() ?: today.year.let { if (m > today.monthValue) it - 1 else it }
        if (y !in 1970..2100) return null
        return month(LocalDate.of(y, m, 1))
    }

    /** Counts written as words, per language; digits are read as they are. */
    private val NUMBERS: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "o" to 1, "un" to 1, "unu" to 1, "una" to 1, "doi" to 2, "două" to 2, "doua" to 2, "trei" to 3, "patru" to 4,
        "cinci" to 5, "șase" to 6, "sase" to 6, "şase" to 6, "șapte" to 7, "sapte" to 7, "şapte" to 7, "opt" to 8,
        "nouă" to 9, "noua" to 9, "zece" to 10, "unsprezece" to 11, "douăsprezece" to 12, "douasprezece" to 12,
        "ein" to 1, "einem" to 1, "einer" to 1, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5,
        "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12,
        "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "sept" to 7, "huit" to 8, "neuf" to 9,
        "dix" to 10, "onze" to 11, "douze" to 12,
        "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9,
        "diez" to 10, "doce" to 12,
        "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9,
        "十" to 10, "十一" to 11, "十二" to 12,
    )

    private fun count(s: String): Int? = s.trim().lowercase().let { it.toIntOrNull() ?: NUMBERS[it] }?.takeIf { it in 1..1200 }

    private enum class Unit { DAY, WEEK, MONTH, YEAR }

    /** "N units ago": that day, that week, that month; a year ago is that same month a year back. */
    private fun ago(n: Int, unit: Unit, today: LocalDate): Span = when (unit) {
        Unit.DAY -> day(today.minusDays(n.toLong()))
        Unit.WEEK -> week(today.minusWeeks(n.toLong()))
        Unit.MONTH -> month(today.minusMonths(n.toLong()))
        Unit.YEAR -> month(today.minusYears(n.toLong()))
    }

    /** [onlyFor]: a word that means something else in another language counts only when the app speaks this one. */
    private class Rule(val regex: Regex, val onlyFor: String? = null, val span: (MatchResult, LocalDate) -> Span?)

    // Latin words stand between non-letters; CJK has no spaces, so its rules match anywhere.
    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    private fun fixed(pattern: String, cjk: Boolean = false, onlyFor: String? = null, value: (LocalDate) -> Span) =
        Rule(Regex(if (cjk) pattern else "$B(?:$pattern)$E", OPT), onlyFor) { _, t -> value(t) }

    private fun counted(pattern: String, cjk: Boolean = false, now: Boolean = false, unitOf: (String) -> Unit?) =
        Rule(Regex(if (cjk) pattern else "$B(?:$pattern)$E", OPT)) { m, t ->
            val n = count(listOf("n", "n2", "n3", "n4", "n5").firstNotNullOfOrNull { k -> runCatching { m.groups[k]?.value }.getOrNull() } ?: "") ?: return@Rule null
            val unitText = listOf("u", "u2", "u3", "u4", "u5").firstNotNullOfOrNull { k -> runCatching { m.groups[k]?.value }.getOrNull() } ?: ""
            val u = unitOf(unitText.lowercase()) ?: return@Rule null
            if (now) last(n, u, t) else ago(n, u, t)
        }

    private val NUM = "(?<n>\\d{1,4}|[\\p{L}]+)"
    private val NUM2 = "(?<n2>\\d{1,4}|[\\p{L}]+)"
    private val NUM3 = "(?<n3>\\d{1,4}|[\\p{L}]+)"
    private val NUM4 = "(?<n4>\\d{1,4}|[\\p{L}]+)"
    private val NUM5 = "(?<n5>\\d{1,4}|[\\p{L}]+)"

    private val RULES: List<Rule> = listOf(
        // Stretches ending today, in every language
        counted("(?:in\\s+)?(?:the\\s+)?(?:last|past|previous)\\s+$NUM\\s+(?<u>days?|weeks?|months?|years?)", now = true) { u ->
            when { u.startsWith("day") -> Unit.DAY; u.startsWith("week") -> Unit.WEEK; u.startsWith("month") -> Unit.MONTH; else -> Unit.YEAR }
        },
        counted("(?:în|in)\\s+ultimele\\s+$NUM\\s+(?<u>zile|săptămâni|saptamani|luni)|(?:în|in)\\s+ultimii\\s+$NUM2\\s+(?<u2>ani)|ultimele\\s+$NUM3\\s+(?<u3>zile|săptămâni|saptamani|luni)|ultimii\\s+$NUM4\\s+(?<u4>ani)", now = true) { u ->
            when { u.startsWith("zi") -> Unit.DAY; u.startsWith("s") -> Unit.WEEK; u.startsWith("lun") -> Unit.MONTH; else -> Unit.YEAR }
        },
        counted("(?:in\\s+)?den\\s+letzten\\s+$NUM\\s+(?<u>tagen|wochen|monaten|jahren)", now = true) { u ->
            when { u.startsWith("tag") -> Unit.DAY; u.startsWith("woche") -> Unit.WEEK; u.startsWith("monat") -> Unit.MONTH; else -> Unit.YEAR }
        },
        counted("(?:ces|les|des)\\s+$NUM\\s+(?<u>derniers\\s+jours|dernières\\s+semaines|dernieres\\s+semaines|derniers\\s+mois|dernières\\s+années|dernieres\\s+annees)|depuis\\s+$NUM2\\s+(?<u2>jours|semaines|mois|ans|années|annees)", now = true) { u ->
            when { "jour" in u -> Unit.DAY; "semaine" in u -> Unit.WEEK; "mois" in u -> Unit.MONTH; else -> Unit.YEAR }
        },
        counted("(?:en\\s+)?los\\s+últimos\\s+$NUM\\s+(?<u>días|dias|meses|años|anos)|(?:en\\s+)?las\\s+últimas\\s+$NUM2\\s+(?<u2>semanas)|(?:en\\s+)?los\\s+ultimos\\s+$NUM3\\s+(?<u3>días|dias|meses|años|anos)|(?:en\\s+)?las\\s+ultimas\\s+$NUM4\\s+(?<u4>semanas)|desde\\s+hace\\s+$NUM5\\s+(?<u5>días|dias|semanas|meses|años|anos)", now = true) { u ->
            when { u.startsWith("d") -> Unit.DAY; u.startsWith("semana") -> Unit.WEEK; u.startsWith("mes") -> Unit.MONTH; else -> Unit.YEAR }
        },
        counted("(?:過去|この|ここ)\\s*(?<n>\\d{1,4}|十[一二]?|[一二三四五六七八九十])\\s*(?<u>日間|日|週間|ヶ月|か月|カ月|ヵ月|年間|年)", cjk = true, now = true) { u ->
            when { u.startsWith("日") -> Unit.DAY; u.startsWith("週") -> Unit.WEEK; u.startsWith("年") -> Unit.YEAR; else -> Unit.MONTH }
        },
        counted("(?:最近|过去|過去|近)\\s*(?<n>\\d{1,4}|十[一二]?|[一二两三四五六七八九十])\\s*(?<u>天|周|个星期|個星期|星期|个月|個月|年)", cjk = true, now = true) { u ->
            when (u) { "天" -> Unit.DAY; "年" -> Unit.YEAR; "个月", "個月" -> Unit.MONTH; else -> Unit.WEEK }
        },
        // Since a year, or a year on its own: "since 2024", "in 2025", "din 2024", "seit 2024", "depuis 2024", "desde 2024"
        Rule(Regex("$B(?:since|from|din|începând\\s+din|incepand\\s+din|seit|ab|depuis|desde)\\s+(?<y>(?:19|20)\\d\\d)$E", OPT)) { m, t ->
            val y = m.groups["y"]!!.value.toInt(); if (y > t.year) null else untilNow(LocalDate.of(y, 1, 1), t)
        },
        Rule(Regex("$B(?:in|în|im|en|de|del|dans|durante|în\\s+anul|in\\s+anul|anul|year|jahr|année|annee|año|ano)?\\s*(?<y>(?:19|20)\\d\\d)$E", OPT)) { m, _ ->
            year(LocalDate.of(m.groups["y"]!!.value.toInt(), 1, 1))
        },
        // A month by name, with or without a year: "August 2026", "in august", "din septembrie", "en mars", "im März", "agosto"
        Rule(Regex("$B(?:in|în|im|en|de|del|dans|luna|din)?\\s*(?<m>$MONTH_NAMES)(?:\\s+(?:of\\s+|din\\s+|de\\s+|del\\s+)?(?<y>(?:19|20)\\d\\d))?$E", OPT)) { m, t ->
            namedMonth(m.groups["m"]!!.value, m.groups["y"]?.value, t)
        },
        Rule(Regex("(?<y>(?:19|20)\\d\\d)年(?<m>1[0-2]|[1-9])月|(?<m2>1[0-2]|[1-9])月", OPT)) { m, t ->
            val mo = (m.groups["m"] ?: m.groups["m2"])!!.value.toInt()
            val y = m.groups["y"]?.value?.toInt() ?: t.year.let { if (mo > t.monthValue) it - 1 else it }
            month(LocalDate.of(y, mo, 1))
        },
        Rule(Regex("(?<y>(?:19|20)\\d\\d)年(?![0-9]{1,2}月)", OPT)) { m, _ -> year(LocalDate.of(m.groups["y"]!!.value.toInt(), 1, 1)) },
        // English
        counted("$NUM\\s+(?<u>days?|weeks?|months?|years?)\\s+ago") { u ->
            when { u.startsWith("day") -> Unit.DAY; u.startsWith("week") -> Unit.WEEK; u.startsWith("month") -> Unit.MONTH; else -> Unit.YEAR }
        },
        fixed("the\\s+day\\s+before\\s+yesterday") { day(it.minusDays(2)) },
        fixed("yesterday") { day(it.minusDays(1)) },
        fixed("today") { day(it) },
        fixed("(?:last|previous)\\s+week") { week(it.minusWeeks(1)) },
        fixed("this\\s+week") { thisWeek(it) },
        fixed("(?:last|previous)\\s+month") { month(it.minusMonths(1)) },
        fixed("this\\s+month") { month(it) },
        fixed("(?:last|previous)\\s+year") { year(it.minusYears(1)) },
        fixed("this\\s+year") { year(it) },
        // Romanian, with and without diacritics
        counted("acum\\s+$NUM\\s+(?<u>zi|zile|zil|săptămână|saptamana|săptămâni|saptamani|lună|luna|luni|an|ani)") { u ->
            when { u.startsWith("zi") -> Unit.DAY; u.startsWith("s") -> Unit.WEEK; u.startsWith("lun") -> Unit.MONTH; else -> Unit.YEAR }
        },
        fixed("alaltăieri|alaltaieri") { day(it.minusDays(2)) },
        fixed("ieri") { day(it.minusDays(1)) },
        fixed("astăzi|astazi|azi") { day(it) },
        fixed("(?:săptămâna|saptamana)\\s+(?:trecută|trecuta)") { week(it.minusWeeks(1)) },
        fixed("(?:săptămâna|saptamana)\\s+(?:asta|aceasta|curentă|curenta)") { thisWeek(it) },
        fixed("luna\\s+(?:trecută|trecuta)") { month(it.minusMonths(1)) },
        fixed("luna\\s+(?:asta|aceasta|curentă|curenta)") { month(it) },
        fixed("anul\\s+trecut") { year(it.minusYears(1)) },
        fixed("anul\\s+(?:ăsta|asta|acesta|curent)") { year(it) },
        // German
        counted("vor\\s+$NUM\\s+(?<u>tag|tagen|woche|wochen|monat|monaten|jahr|jahren)") { u ->
            when { u.startsWith("tag") -> Unit.DAY; u.startsWith("woche") -> Unit.WEEK; u.startsWith("monat") -> Unit.MONTH; else -> Unit.YEAR }
        },
        fixed("vorgestern") { day(it.minusDays(2)) },
        fixed("gestern") { day(it.minusDays(1)) },
        fixed("heute") { day(it) },
        fixed("letzte\\s+woche|vergangene\\s+woche") { week(it.minusWeeks(1)) },
        fixed("diese\\s+woche") { thisWeek(it) },
        fixed("letzten\\s+monat|vergangenen\\s+monat|letzter\\s+monat") { month(it.minusMonths(1)) },
        fixed("diesen\\s+monat|dieser\\s+monat") { month(it) },
        fixed("letztes\\s+jahr|vergangenes\\s+jahr") { year(it.minusYears(1)) },
        fixed("dieses\\s+jahr") { year(it) },
        // French
        counted("il\\s+y\\s+a\\s+$NUM\\s+(?<u>jours?|semaines?|mois|ans?|années?|annees?)") { u ->
            when { u.startsWith("jour") -> Unit.DAY; u.startsWith("semaine") -> Unit.WEEK; u == "mois" -> Unit.MONTH; else -> Unit.YEAR }
        },
        fixed("avant-hier|avant\\s+hier") { day(it.minusDays(2)) },
        // "hier" is also German for "here".
        fixed("hier", onlyFor = "fr") { day(it.minusDays(1)) },
        fixed("aujourd['’]hui") { day(it) },
        fixed("la\\s+semaine\\s+(?:dernière|derniere|passée|passee)") { week(it.minusWeeks(1)) },
        fixed("cette\\s+semaine") { thisWeek(it) },
        fixed("le\\s+mois\\s+(?:dernier|passé|passe)") { month(it.minusMonths(1)) },
        fixed("ce\\s+mois(?:-ci)?") { month(it) },
        fixed("l['’](?:année|annee)\\s+(?:dernière|derniere|passée|passee)|l['’]an\\s+(?:dernier|passé|passe)") { year(it.minusYears(1)) },
        fixed("cette\\s+(?:année|annee)") { year(it) },
        // Spanish
        counted("hace\\s+$NUM\\s+(?<u>días?|dias?|semanas?|mes|meses|años?|anos?)") { u ->
            when { u.startsWith("d") -> Unit.DAY; u.startsWith("semana") -> Unit.WEEK; u.startsWith("mes") -> Unit.MONTH; else -> Unit.YEAR }
        },
        fixed("anteayer|antier") { day(it.minusDays(2)) },
        fixed("ayer") { day(it.minusDays(1)) },
        fixed("hoy") { day(it) },
        fixed("la\\s+semana\\s+pasada") { week(it.minusWeeks(1)) },
        fixed("esta\\s+semana") { thisWeek(it) },
        fixed("el\\s+mes\\s+pasado") { month(it.minusMonths(1)) },
        fixed("este\\s+mes") { month(it) },
        fixed("el\\s+(?:año|ano)\\s+pasado") { year(it.minusYears(1)) },
        fixed("este\\s+(?:año|ano)") { year(it) },
        // Japanese
        counted("(?<n>\\d{1,4}|十[一二]?|[一二三四五六七八九十])\\s*(?<u>日|週間|週|ヶ月|か月|カ月|ヵ月|年)前", cjk = true) { u ->
            when (u) { "日" -> Unit.DAY; "週間", "週" -> Unit.WEEK; "年" -> Unit.YEAR; else -> Unit.MONTH }
        },
        fixed("一昨日|おととい", cjk = true) { day(it.minusDays(2)) },
        fixed("昨日|きのう", cjk = true) { day(it.minusDays(1)) },
        fixed("今日|きょう", cjk = true) { day(it) },
        fixed("先週", cjk = true) { week(it.minusWeeks(1)) },
        fixed("今週", cjk = true) { thisWeek(it) },
        fixed("先月", cjk = true) { month(it.minusMonths(1)) },
        fixed("今月", cjk = true) { month(it) },
        fixed("去年|昨年", cjk = true) { year(it.minusYears(1)) },
        fixed("今年", cjk = true) { year(it) },
        // Chinese
        counted("(?<n>\\d{1,4}|十[一二]?|[一二两三四五六七八九十])\\s*(?<u>天|周|个星期|個星期|星期|个月|個月|年)前", cjk = true) { u ->
            when (u) { "天" -> Unit.DAY; "年" -> Unit.YEAR; "个月", "個月" -> Unit.MONTH; else -> Unit.WEEK }
        },
        fixed("前天", cjk = true) { day(it.minusDays(2)) },
        fixed("昨天", cjk = true) { day(it.minusDays(1)) },
        fixed("今天", cjk = true) { day(it) },
        fixed("上周|上个星期|上星期|上個星期", cjk = true) { week(it.minusWeeks(1)) },
        fixed("本周|这周|这个星期|這週", cjk = true) { thisWeek(it) },
        fixed("上个月|上個月|上月", cjk = true) { month(it.minusMonths(1)) },
        fixed("这个月|這個月|本月", cjk = true) { month(it) },
        fixed("今年", cjk = true) { year(it) },
    )

    /** The first words of time in [text] (earliest in it) and the days they mean, counted from [today]; null when there are none. [language] is the app's. */
    fun parse(text: String, language: String, today: LocalDate = LocalDate.now()): Parsed? {
        if (text.isBlank()) return null
        var best: Pair<MatchResult, Span>? = null
        for (rule in RULES) {
            if (rule.onlyFor != null && rule.onlyFor != language) continue
            for (m in rule.regex.findAll(text)) {
                val span = rule.span(m, today) ?: continue
                if (best == null || m.range.first < best.first.range.first) best = m to span
                break
            }
        }
        val (m, span) = best ?: return null
        val rest = (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim()
        return Parsed(rest, span)
    }
}
