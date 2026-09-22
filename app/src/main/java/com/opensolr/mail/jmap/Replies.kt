package com.opensolr.mail.jmap

import com.opensolr.mail.AppText
import com.opensolr.mail.R
import com.opensolr.mail.data.Address
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Message

/** Addressing, subject and quoting for replies and forwards. */
object Replies {

    enum class Kind { REPLY, REPLY_ALL, FORWARD }

    data class Draft(
        val identity: MailDb.Identity?,
        val to: List<Address>,
        val cc: List<Address>,
        val subject: String,
        val quote: String,
        val inReplyTo: String,
        val references: String,
    )

    /** The identity a reply goes out from: the one the message was sent to, else the account's first. */
    fun identityFor(original: Message?, identities: List<MailDb.Identity>): MailDb.Identity? {
        if (original != null) {
            val addressed = (original.to + original.cc + original.bcc).map { it.email.lowercase() }.toSet()
            identities.firstOrNull { it.email.lowercase() in addressed }?.let { return it }
        }
        return identities.firstOrNull()
    }

    fun build(kind: Kind, m: Message, identities: List<MailDb.Identity>): Draft {
        val identity = identityFor(m, identities)
        val mine = identities.map { it.email.lowercase() }.toSet()
        val replyTarget = m.replyTo.ifEmpty { m.from }
        val sentByMe = m.from.any { it.email.lowercase() in mine }
        val (to, cc) = when (kind) {
            Kind.FORWARD -> emptyList<Address>() to emptyList()
            Kind.REPLY -> (if (sentByMe) m.to else replyTarget) to emptyList()
            Kind.REPLY_ALL -> {
                val t = if (sentByMe) m.to else (replyTarget + m.to)
                val first = t.filterNot { it.email.lowercase() in mine }.distinctBy { it.email.lowercase() }
                val seen = first.map { it.email.lowercase() }.toSet()
                first to m.cc.filterNot { it.email.lowercase() in mine || it.email.lowercase() in seen }.distinctBy { it.email.lowercase() }
            }
        }
        val subject = when (kind) {
            Kind.FORWARD -> if (m.subject.startsWith("Fwd:", true)) m.subject else "Fwd: ${m.subject}"
            else -> if (m.subject.startsWith("Re:", true)) m.subject else "Re: ${m.subject}"
        }
        val body = m.bodyText?.takeIf { it.isNotBlank() } ?: m.preview
        val date = com.opensolr.mail.ui.fmtDate(m.received)
        val quote = if (kind == Kind.FORWARD) {
            "\n\n" + AppText.s(R.string.fwd_header) + "\n" +
                AppText.s(R.string.from) + ": " + m.from.joinToString { it.formatted() } + "\n" +
                AppText.s(R.string.fwd_date) + ": " + date + "\n" +
                AppText.s(R.string.subject) + ": " + m.subject + "\n" +
                AppText.s(R.string.to) + ": " + m.to.joinToString { it.formatted() } + "\n\n" + body
        } else {
            "\n\n" + AppText.s(R.string.quote_wrote, date, m.sender?.label.orEmpty()) + "\n" + body.lines().joinToString("\n") { "> $it" }
        }
        val refs = if (kind == Kind.FORWARD) "" else (m.references + " " + (if (m.messageId.isNotBlank()) "<${m.messageId}>" else "")).trim()
        return Draft(identity, to, cc, subject, quote, if (kind == Kind.FORWARD) "" else m.messageId, refs)
    }
}
