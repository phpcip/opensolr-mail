package com.opensolr.mail.jmap

import androidx.core.text.HtmlCompat

object Html {

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Plain text shown as HTML, keeping line breaks. */
    fun fromText(s: String): String = "<div style=\"$TEXT_STYLE\">" + escape(s) + "</div>"

    private const val TEXT_STYLE = "white-space:pre-wrap;word-wrap:break-word"

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

    // Where the quoted history can begin. A container holds the quote (Gmail, Apple Mail, Fastmail, Yahoo); after a
    // header (Outlook) the original follows as it is; an attribution line ("On ... wrote:", Thunderbird) introduces it.
    // Containers that hold the quote, as each mail program marks it: any blockquote at all (Apple Mail, Zoho, Roundcube,
    // Horde, Yandex, Mail.ru, QQ, K-9, FairEmail, Spark, Airmail and most others), Gmail, Fastmail, Yahoo, Thunderbird
    // forwards, Proton, Zoho Mail, Front, Freshdesk, Zendesk, HubSpot, Mixmax, Streak, Superhuman, Hey, AOL, Yandex.
    private val CONTAINER = org.jsoup.select.QueryParser.parse(listOf(
        "blockquote", "div.gmail_quote", "div.gmail_quote_container", "div.gmail_extra", "div#qt", "blockquote#qt",
        "div.yahoo_quoted", "div.yahoo-quoted", "div[id^=yqt]", "div.moz-forward-container", "div.protonmail_quote",
        "div.zmail_extra", "div.front-blockquote", "div.freshdesk_quote", "div.zd_comment_quote", "div.hubspot_quote",
        "div.mixmax-quote", "div.streak-quote", "div.superhuman_quote", "div.hey-quote", "div.aolReplacedBody",
        "div.yandex_quote", "div.mail-quote-collapse", "div.k9mail-quote", "div.fairemail_quote", "div.spark_quote",
        "div.airmail_quote", "div.bluemail_quote", "div.emclient_quote", "div.mailbird_quote", "div.newton_quote",
        "div.polymail_quote", "div.edison_quote", "div.notion_quote", "div.intercom-h2b-blockquote", "div.quoted-text",
        "div.quote", "div.replied-text", "div.reply-quote", "div.original-message", "div.previous-message",
        "div.js-quoted-text", "div.im_quote", "div.wm_quote", "div.mail_quote", "div.mailQuote", "div.quoteblock",
        "div.gmail-quote", "div.gmail-blockquote", "pre.moz-quote-pre", "div.moz-quote",
    ).joinToString(", "))
    // Headers after which the original follows as it is: Outlook (web, desktop, Mac, mobile), Zoho's marker, Hotmail.
    private val HEADER = org.jsoup.select.QueryParser.parse(listOf(
        "div#divRplyFwdMsg", "div#x_divRplyFwdMsg", "div#appendonsend", "div#x_appendonsend", "hr#stopSpelling",
        "div.OutlookMessageHeader", "div#OLK_SRC_BODY_SECTION", "div#x_OLK_SRC_BODY_SECTION", "div.OLK_SRC_BODY_SECTION",
        "div[title^=beforequote]", "div.zmail_extra_hr", "div.hmmessage_quote", "div#ReplyFwdMsg", "div.ReplyFwdMsg",
        "div.outlook_quote", "div#x_ReplyFwdMsg", "div.ms-outlook-quote", "div.msg-quote", "div.lotus-quote",
        "table.lotus-quote", "div.gw-quote", "div.groupwise-quote", "div.notes_quote", "div.ibm-notes-quote",
    ).joinToString(", "))
    // Lines that introduce the quote under them: Thunderbird, Gmail's attribution, Zoho, Proton, Yahoo, Front.
    private val ATTRIBUTION = org.jsoup.select.QueryParser.parse(listOf(
        "div.moz-cite-prefix", "div.gmail_attr", "div.zmail_quote_header", "div.protonmail_quote_header",
        "div.yahoo_quoted_header", "div.front-attribution", "div.quote-header", "div.reply-header", "div.quoted-header",
        "p.moz-cite-prefix", "span.moz-cite-prefix", "div.ymail_quote_attr", "div.yahoo-quote-attr", "div.attribution",
        "div.reply_attribution", "div.replyHeader", "div.quoteHeader", "p.reply-header", "div.gmail-attr",
    ).joinToString(", "))
    // Anything quoted, marked or not: nothing inside it is a word of the message itself.
    private val QUOTED = org.jsoup.select.QueryParser.parse("blockquote, div.gmail_quote, div.gmail_quote_container, div.gmail_extra, div#qt, div.yahoo_quoted, div.yahoo-quoted, div[id^=yqt], div.moz-forward-container, div.protonmail_quote, div.zmail_extra, div.front-blockquote, div.freshdesk_quote, div.zd_comment_quote, div.hubspot_quote, div.mixmax-quote, div.streak-quote, div.superhuman_quote, div.hey-quote, div.aolReplacedBody, div.yandex_quote, div.mail-quote-collapse, div.k9mail-quote, div.fairemail_quote, div.spark_quote, div.airmail_quote, div.bluemail_quote, div.emclient_quote, div.mailbird_quote, div.newton_quote, div.polymail_quote, div.edison_quote, div.notion_quote, div.intercom-h2b-blockquote, div.quoted-text, div.quote, div.replied-text, div.reply-quote, div.original-message, div.previous-message, div.js-quoted-text, div.im_quote, div.wm_quote, div.mail_quote, div.mailQuote, div.quoteblock, div.gmail-quote, div.gmail-blockquote, pre.moz-quote-pre, div.moz-quote")
    private val SIGNATURE = org.jsoup.select.QueryParser.parse("div.gmail_signature, div#ms-outlook-mobile-signature, div.moz-signature, pre.moz-signature, div.zmail_signature, div.protonmail_signature, div#Signature, div.signature, div.sig, div.email-signature, div.mail-signature, div.ymail_android_signature, div.yahoo_signature, div.k9mail-signature, div.fairemail_signature, div.spark_signature, div.airmail_signature, div.bluemail_signature, div.front-signature, div.hubspot_signature, div.mixmax-signature, div.superhuman_signature")
    private val SIGNATURE_PREFIX = org.jsoup.select.QueryParser.parse("span.gmail_signature_prefix, div.gmail_signature_prefix")
    private val FORWARDED = Regex("(?i)-{2,}\\s*(Forwarded message|Weitergeleitete Nachricht|Message transféré|Mensaje reenviado|Mensagem encaminhada|Messaggio inoltrato|Doorgestuurd bericht|Vidarebefordrat meddelande|Videresendt meddelelse|Videresendt melding|Wiadomość przekazana|Пересылаемое сообщение|Mesaj redirecționat|Mesaj redirectionat|Přeposlaná zpráva|Továbbított üzenet|Välitetty viesti|İletilmiş ileti|転送されたメッセージ|转发的邮件|轉寄的郵件|전달된 메시지)|Begin forwarded message|Anfang der weitergeleiteten Nachricht|Début du message réexpédié|Inicio del mensaje reenviado|Início da mensagem encaminhada|Inizio messaggio inoltrato|Begin doorgestuurd bericht|Mesaj redirec")
    // the words that end an attribution line, in the languages mail programs speak
    private const val WROTE_WORDS = "wrote|a scris|au scris|scria|schrieb|schrieben|a écrit|ont écrit|écrivait|escribió|escribieron|escreveu|escreveram|ha scritto|hanno scritto|schreef|schreven|skrev|skrifaði|napisał|napisała|napisali|написал|написала|написали|писал|писала|пише|napsal|napsala|napsali|írta|írták|kirjoitti|kirjoittivat|yazdı|yazdılar|έγραψε|כתב|כתבה|كتب|كتبت|書きました|写道|寫道|작성|wrote the following"
    private const val ON_WORDS = "on|le|am|el|il|em|op|den|pe|în|in|la|w dniu|dnia|dne|dňa|dňom|ngày|vào|den|the|il giorno|a|às|às|kl\\.|klo|tarihinde|στις|ב|في|el día|o|u|w|на|от|от:|-"
    // "On ... wrote:" in every form: with or without dashes, ":" or "----" at the end, any language; GroupWise ">>> ... >>>"
    private val WROTE = Regex("(?i)^(?:(?:-{2,}|>{2,}|\\*{1,2})\\s*(?:$ON_WORDS)?.{2,300}?\\b(?:$WROTE_WORDS)\\b.{0,60}?(?:[:：]|-{2,}|>{2,}|\\*{1,2})?|(?:$ON_WORDS)\\b.{2,300}?\\b(?:$WROTE_WORDS)\\b.{0,60}?[:：]|.{2,300}?\\b(?:$WROTE_WORDS)\\b.{0,20}?[:：])\\s*$")
    private val ON_START = Regex("(?i)^(?:-{2,}\\s*)?(?:$ON_WORDS)\\b")
    private val GROUPWISE = Regex("^>{3}\\s.{4,300}>{3}\\s*$")
    private const val ORIGINAL_WORDS = "Original Message|Original message|Original-Nachricht|Ursprüngliche Nachricht|Ursprungliche Nachricht|Message d'origine|Message original|Mensaje original|Mensagem original|Messaggio originale|Oorspronkelijk bericht|Oorspronkelijke e-mail|Ursprungligt meddelande|Oprindelig meddelelse|Opprinnelig melding|Wiadomość oryginalna|Oryginalna wiadomość|Исходное сообщение|Оригинальное сообщение|Mesaj original|Mesajul original|Původní zpráva|Pôvodná správa|Eredeti üzenet|Alkuperäinen viesti|Orijinal İleti|Orijinal mesaj|Αρχικό μήνυμα|הודעה מקורית|الرسالة الأصلية|元のメッセージ|原始邮件|原始郵件|원본 메시지|Original Email|Original E-Mail|Original Appointment|Reply message|Original text"
    private val HEADER_LINE = Regex("(?i)^(?:-{2,}|_{2,}|={2,}|\\*{2,})?\\s*(?:(?:$ORIGINAL_WORDS)|(?:${FORWARDED.pattern.removePrefix("(?i)")}))\\s*(?:-{2,}|_{2,}|={2,}|\\*{2,})?\\s*$")
    private const val FROM_WORDS = "From|Von|De|Da|Van|Från|Fra|Od|От|Lähettäjä|Kimden|Από|מאת|من|差出人|发件人|發件人|寄件者|보낸 사람|Expeditor|Afzender|Sender|Avsändare|Odesílatel|Feladó|Gönderen|Autor"
    private const val SENT_WORDS = "Sent|Gesendet|Envoyé|Enviado|Enviada|Inviato|Verzonden|Skickat|Sendt|Wysłano|Отправлено|Lähetetty|Gönderilme|Gönderildi|Στάλθηκε|נשלח|تاريخ الإرسال|送信日時|发送时间|發送時間|寄件日期|보낸 날짜|Date|Datum|Data|Fecha|Дата|Dátum|Päiväys|Tarih|Trimis|Odesláno|Küldve|Skickad|Sendt"
    private val FROM_HEADER = Regex("(?i)^(?:$FROM_WORDS)\\s*[:：].{1,560}\\b(?:$SENT_WORDS)\\s*[:：]")
    private val TEXT_WROTE = Regex("(?i)^\\s*(?:(?:-{2,}|>{2,})\\s*(?:$ON_WORDS)?.{2,300}?\\b(?:$WROTE_WORDS)\\b.{0,60}?(?:[:：]|-{2,}|>{2,})?|(?:$ON_WORDS)\\b.{2,300}?\\b(?:$WROTE_WORDS)\\b.{0,60}?[:：]|.{2,300}?\\b(?:$WROTE_WORDS)\\b.{0,20}?[:：])\\s*$")
    private val TEXT_HEADER = Regex("(?i)^\\s*(?:(?:-{2,}|_{2,}|={2,}|\\*{2,})?\\s*(?:(?:$ORIGINAL_WORDS)|(?:${FORWARDED.pattern.removePrefix("(?i)")}))\\s*(?:-{2,}|_{2,}|={2,}|\\*{2,})?|(?:$FROM_WORDS)\\s*[:：]\\s.+)\\s*$")
    private const val KIND_HEADER = 1
    private const val KIND_CONTAINER = 2
    private const val KIND_ATTRIBUTION = 3
    private const val ATTRIBUTION_MAX = 300

    /**
     * The message as written, with the quoted history below it folded under [label]: a click on the
     * label opens it. The history is the quote at the end, after which nothing of the message itself
     * follows; found by the marks mail programs put on a quote (Gmail, Apple Mail, Outlook, Fastmail,
     * Thunderbird, Yahoo) or by an "On ... wrote:" / "Original Message" line. A quote the writer answered
     * under it, replying between the lines, is part of the message and stays in sight. A message that is
     * nothing but a quote (a forward) is shown whole.
     */
    fun foldQuotes(html: String, label: String, hide: String): String = runCatching {
        val doc = org.jsoup.Jsoup.parseBodyFragment(html)
        // Pretty printing would collapse the line breaks of pre-wrap text.
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()
        // A plain text message only wrapped by [fromText] is folded line by line, like text.
        val blocks = body.children()
        if (blocks.isNotEmpty() && body.ownText().isBlank() && blocks.all { it.normalName() == "div" && it.attr("style") == TEXT_STYLE && it.children().isEmpty() }) {
            return@runCatching fromTextFolded(blocks.joinToString("\n\n") { it.wholeText() }, label, hide)
        }
        var start = quoteStart(body) ?: return@runCatching html
        // Before the quote there must be something of the message itself, or there is nothing to fold.
        if (!wordsBefore(body, start, null)) return@runCatching html
        // The "... wrote:" line right above a quote, in any language, folds with it: a short line ending with ":",
        // with words of the message above it.
        if (start.`is`(CONTAINER)) {
            var above = start.previousElementSibling()
            while (above != null && above.normalName() == "br") above = above.previousElementSibling()
            if (above != null && above.children().none { it.isBlock } && above.text().trim().let { it.length <= ATTRIBUTION_MAX && (it.endsWith(":") || it.endsWith("：") || it.endsWith("--") || WROTE.matches(it)) } && wordsBefore(body, start, above)) start = above
        }
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

    /**
     * The element where the quoted history begins: the first one after which no word of the message itself
     * follows. A header is followed by the original as it is, so it always counts; an attribution line counts
     * when the quote it introduces is the rest of the message, or when nothing after it is marked as a quote at all.
     */
    private fun quoteStart(body: org.jsoup.nodes.Element): org.jsoup.nodes.Element? {
        for (e in body.getAllElements()) {
            // inside a quote everything is quoted
            if (e === body || e.parents().any { it.`is`(QUOTED) }) continue
            val kind = when {
                e.`is`(HEADER) -> KIND_HEADER
                e.`is`(CONTAINER) -> KIND_CONTAINER
                e.`is`(ATTRIBUTION) -> KIND_ATTRIBUTION
                else -> writtenKind(e)
            } ?: continue
            val found = when (kind) {
                KIND_HEADER -> true
                KIND_CONTAINER -> !wordsAfter(body, e)
                else -> !quotedAfter(body, e) || !wordsAfter(body, e)
            }
            if (found) return e
        }
        return null
    }

    // A written "On ... wrote:" line, an "Original Message" / "Forwarded message" line, or Outlook's short header
    // block (From: with Sent: or Date:).
    private fun writtenKind(e: org.jsoup.nodes.Element): Int? {
        val own = e.ownText().trim()
        if (own.isNotEmpty()) {
            val first = e.wholeOwnText().trim().lineSequence().firstOrNull().orEmpty().trim()
            if (HEADER_LINE.matches(own) || HEADER_LINE.matches(first) || GROUPWISE.matches(own)) return KIND_HEADER
            if (WROTE.matches(own) || WROTE.matches(first)) return KIND_ATTRIBUTION
            // the line with its inline parts (a name in <b>, an address in <a>): Zoho, Yandex, Outlook mobile
            if (e.children().none { it.isBlock }) {
                val line = e.text().trim()
                if (line.length <= ATTRIBUTION_MAX && (HEADER_LINE.matches(line) || GROUPWISE.matches(line))) return KIND_HEADER
                if (line.length <= ATTRIBUTION_MAX && WROTE.matches(line)) return KIND_ATTRIBUTION
            }
        }
        // Outlook's header block in any language: From: ... Sent:/Date: ...
        val head = firstText(e)
        if (head != null && head.length <= 24 && FROM_START.containsMatchIn(head)) {
            val all = e.text().trim()
            if (all.length < 600 && FROM_HEADER.containsMatchIn(all)) return KIND_HEADER
        }
        return null
    }
    private val FROM_START = Regex("(?i)^(?:$FROM_WORDS)\\s*[:：]?$")

    // the first words inside [e], in reading order
    private fun firstText(e: org.jsoup.nodes.Element): String? {
        for (n in e.childNodes()) {
            when (n) {
                is org.jsoup.nodes.TextNode -> if (!n.isBlank) return n.text().trim()
                is org.jsoup.nodes.Element -> firstText(n)?.let { return it }
            }
        }
        return null
    }

    // the nodes after [e] in reading order, up to the end of the body
    private fun after(body: org.jsoup.nodes.Element, e: org.jsoup.nodes.Element): Sequence<org.jsoup.nodes.Node> = sequence {
        var node: org.jsoup.nodes.Node? = e
        while (node != null && node !== body) {
            var next = node.nextSibling()
            while (next != null) { yield(next); next = next.nextSibling() }
            node = node.parent()
        }
    }

    // words of the message itself (outside any quote) in [n]; punctuation alone ("...", "---") is none
    private fun hasWords(n: org.jsoup.nodes.Node): Boolean = when (n) {
        is org.jsoup.nodes.TextNode -> n.text().any { it.isLetterOrDigit() }
        is org.jsoup.nodes.Element -> !n.`is`(QUOTED) && !n.`is`(SIGNATURE) && n.childNodes().any { hasWords(it) }
        else -> false
    }

    // the "-- " line: from it down there is only the signature
    private fun isSigDash(n: org.jsoup.nodes.Node): Boolean = when (n) {
        is org.jsoup.nodes.TextNode -> n.text().trim() == "--"
        is org.jsoup.nodes.Element -> n.`is`(SIGNATURE_PREFIX) || (n.children().isEmpty() && n.text().trim() == "--")
        else -> false
    }

    private fun holdsQuote(n: org.jsoup.nodes.Node): Boolean = n is org.jsoup.nodes.Element && (n.`is`(QUOTED) || n.childNodes().any { holdsQuote(it) })

    // words of the message after [e]; a signature under the quote (Gmail puts it there) is none
    private fun wordsAfter(body: org.jsoup.nodes.Element, e: org.jsoup.nodes.Element): Boolean {
        for (n in after(body, e)) {
            if (isSigDash(n)) return false
            if (hasWords(n)) return true
        }
        return false
    }

    private fun quotedAfter(body: org.jsoup.nodes.Element, e: org.jsoup.nodes.Element): Boolean = after(body, e).any { holdsQuote(it) }

    // words before [start] (not inside it), leaving out [except] and what is in it
    private fun wordsBefore(body: org.jsoup.nodes.Element, start: org.jsoup.nodes.Element, except: org.jsoup.nodes.Element?): Boolean {
        for (n in body.getAllElements()) {
            if (n === start) return false
            if (n === body || n === except) continue
            if (n.parents().any { it === start || it === except }) continue
            if (n.ownText().isNotBlank()) return true
        }
        return false
    }

    /** [folded] without the quoted history: the message as it reads with the fold closed. */
    fun dropFolded(folded: String): String = runCatching {
        val body = org.jsoup.Jsoup.parseBodyFragment(folded).apply { outputSettings().prettyPrint(false) }.body()
        body.select("details.osq").remove()
        body.html()
    }.getOrDefault(folded)

    /** The quoted history alone, out of [folded]: what the fold holds. */
    fun foldedPart(folded: String): String = runCatching {
        val body = org.jsoup.Jsoup.parseBodyFragment(folded).apply { outputSettings().prettyPrint(false) }.body()
        val d = body.select("details.osq").first() ?: return@runCatching ""
        d.select("> summary").remove()
        d.html()
    }.getOrDefault("")

    /** Whether [folded] holds a fold. */
    fun hasFold(folded: String): Boolean = folded.contains("<details class=\"osq\">")

    /** The message's own words as plain text: the quote found by [foldQuotes] is dropped; a forward stays whole. */
    fun withoutQuotes(html: String): String = runCatching {
        val body = org.jsoup.Jsoup.parseBodyFragment(foldQuotes(html, "", "")).apply { outputSettings().prettyPrint(false) }.body()
        // A forward's original is what was sent along, not a quote of this conversation: it stays.
        if (body.select("details.osq").text().take(300).contains(FORWARDED)) return@runCatching plain(html)
        body.select("details.osq").remove()
        plain(body.html())
    }.getOrDefault(plain(html))

    /**
     * Plain text shown as HTML, the quoted history at the end folded under [label]: the "> " lines and the line that
     * introduces them, after which nothing of the message itself follows. A "> " block answered under it stays in
     * sight; after an "Original Message" / "Forwarded message" / "From:" line the original follows as it is.
     */
    fun fromTextFolded(text: String, label: String, hide: String): String {
        val lines = text.lines()
        fun quoted(l: String) = l.trimStart().startsWith(">")
        fun intro(l: String) = TEXT_WROTE.matches(l) || TEXT_HEADER.matches(l) || GROUPWISE.matches(l.trim())
        fun wordless(l: String) = l.none { it.isLetterOrDigit() }
        // a "-- " line with no "> " line under it: from it down there is only the signature
        val sigDash = lines.indexOfLast { it.trimEnd() == "--" }.takeIf { sd -> sd >= 0 && (sd + 1 until lines.size).none { quoted(lines[it]) } }
        // from [tail] down there is nothing but quote: blank, "> " or introducing lines
        var tail = sigDash ?: lines.size
        while (tail > 0 && (wordless(lines[tail - 1]) || quoted(lines[tail - 1]) || intro(lines[tail - 1]))) tail--
        var at = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (TEXT_HEADER.matches(l) || GROUPWISE.matches(l.trim())) { at = i; break }
            if (TEXT_WROTE.matches(l)) {
                // introduces "> " lines: only when they are the rest; else the original follows unmarked
                if (i >= tail || (i + 1 until lines.size).none { quoted(lines[it]) }) { at = i; break }
                continue
            }
            if (quoted(l) && i >= tail) { at = i; break }
        }
        // The "... wrote:" line right above the "> " lines, in any language, folds with them.
        if (at > 0 && quoted(lines[at]) && lines[at - 1].trimEnd().let { it.endsWith(":") || it.endsWith("：") || it.endsWith("--") }) at--
        // An attribution wrapped over two or three lines: back to the line that opens it ("On ...")
        if (at > 0 && TEXT_WROTE.matches(lines[at]) && !ON_START.containsMatchIn(lines[at].trim())) {
            var k = at - 1
            var joined = lines[at].trim()
            while (k >= 0 && k >= at - 2 && lines[k].isNotBlank() && !quoted(lines[k])) {
                joined = lines[k].trim() + " " + joined
                if (joined.length <= ATTRIBUTION_MAX && ON_START.containsMatchIn(lines[k].trim())) { at = k; break }
                k--
            }
        }
        if (at <= 0 || lines.take(at).all { it.isBlank() }) return fromText(text)
        val head = lines.take(at).joinToString("\n").trimEnd()
        val tailText = lines.drop(at).joinToString("\n")
        return fromText(head) + "<details class=\"osq\"><summary><span class=\"s\">" + escape(label) + "</span><span class=\"h\">" + escape(hide) + "</span></summary>" + fromText(tailText) + "</details>"
    }
}
