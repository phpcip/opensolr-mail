package com.opensolr.mail.data

import org.json.JSONArray
import org.json.JSONObject

data class Address(val name: String, val email: String) {
    val label: String get() = name.ifBlank { email }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("email", email)

    /** "Name <email>" or the bare address. */
    fun formatted(): String = if (name.isBlank()) email else "\"${name.replace("\"", "")}\" <$email>"

    companion object {
        fun listFrom(a: JSONArray?): List<Address> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Address(it.optString("name").takeIf { n -> n != "null" }.orEmpty(), it.optString("email")) }
                    ?.takeIf { it.email.isNotBlank() }
            }
        }

        fun listFromJson(text: String?): List<Address> =
            if (text.isNullOrEmpty()) emptyList() else runCatching { listFrom(JSONArray(text)) }.getOrDefault(emptyList())

        fun listToJson(list: List<Address>): String = JSONArray(list.map { it.toJson() }).toString()

        /** Parses what a person types in a To/Cc field: comma or semicolon separated, with or without names. */
        fun parseInput(text: String): List<Address> = splitInput(text).mapNotNull { part ->
            val p = part.trim()
            if (p.isEmpty()) return@mapNotNull null
            val m = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>\\s*$").find(p)
            if (m != null) Address(m.groupValues[1].trim(), m.groupValues[2].trim())
            else Address("", p)
        }.filter { it.email.contains('@') }

        /** Recipients split on , ; and new lines, except inside quotes or <>, so "Davison, Kevin" <k@x.com> stays whole. */
        fun splitInput(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            var angle = false
            text.forEach { ch ->
                when {
                    ch == '"' -> { quoted = !quoted; cur.append(ch) }
                    ch == '<' && !quoted -> { angle = true; cur.append(ch) }
                    ch == '>' && !quoted -> { angle = false; cur.append(ch) }
                    (ch == ',' || ch == ';' || ch == '\n') && !quoted && !angle -> { out += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            out += cur.toString()
            return out
        }
    }
}

enum class Role(val jmap: String) {
    INBOX("inbox"), DRAFTS("drafts"), SENT("sent"), ARCHIVE("archive"), JUNK("junk"), TRASH("trash");

    companion object {
        fun of(value: String?): Role? = entries.firstOrNull { it.jmap == value }
    }
}

data class Mailbox(
    val acc: String,
    val id: String,
    val name: String,
    val parentId: String?,
    val role: String?,
    val sortOrder: Int,
    val total: Int,
    val unread: Int,
)

data class Attachment(val blobId: String, val name: String, val type: String, val size: Long, val cid: String?, val inline: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("blob", blobId).put("name", name).put("type", type).put("size", size)
        .put("cid", cid ?: "").put("inline", inline)

    companion object {
        fun listFromJson(text: String?): List<Attachment> {
            if (text.isNullOrEmpty()) return emptyList()
            val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let {
                    Attachment(it.optString("blob"), it.optString("name"), it.optString("type"), it.optLong("size"),
                        it.optString("cid").ifEmpty { null }, it.optBoolean("inline"))
                }
            }
        }
    }
}

data class Message(
    val acc: String,
    val id: String,
    val threadId: String,
    val mailboxIds: Set<String>,
    val seen: Boolean,
    val flagged: Boolean,
    val draft: Boolean,
    val answered: Boolean,
    val received: Long,
    val subject: String,
    val from: List<Address>,
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val replyTo: List<Address>,
    val preview: String,
    val hasAttachment: Boolean,
    val size: Long,
    val messageId: String,
    val inReplyTo: String,
    val references: String,
    val bodyText: String?,
    val bodyHtml: String?,
    val attachments: List<Attachment>,
) {
    val sender: Address? get() = from.firstOrNull()
}

/** One line of a message list: a conversation, represented by its latest message in the view. */
data class ThreadRow(
    val acc: String,
    val threadId: String,
    val latestId: String,
    val subject: String,
    val senders: String,
    val preview: String,
    val received: Long,
    val count: Int,
    val unread: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    val fromName: String = "",
    val fromEmail: String = "",
)

/** Which messages a list shows: a role across every account, or one mailbox. */
sealed class View {
    data class Unified(val role: Role) : View()
    data class Box(val acc: String, val mailboxId: String) : View()
    object Flagged : View()
}

/** What the Opensolr plan allows and how much of it is used, from get_account_summary. */
data class AccountLimits(
    val plan: String,
    val price: Double,
    val recurrence: String,
    val vectorAllowed: Boolean,
    val maxAiRequests: Int,
    val aiRequestsUsed: Int,
    val indexLimit: Int,
    val indexesUsed: Int,
    val diskLimitMb: Double,
    val diskUsedMb: Double,
    val bandwidthLimitMb: Double,
    val bandwidthUsedMb: Double,
    val indexedDocs: Long,
    val refreshedAt: Long,
) {
    val planLabel: String get() {
        if (price <= 0.0) return plan.ifBlank { "Opensolr" }
        val amount = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(Math.round(price))
        val period = recurrence.trim().lowercase().let { r -> if (r.isBlank()) "month" else if (r.startsWith("1 ")) r.removePrefix("1 ") else r }
        return "\u20ac$amount / $period"
    }

    val diskFull: Boolean get() = diskLimitMb > 0 && diskUsedMb >= diskLimitMb
    val bandwidthFull: Boolean get() = bandwidthLimitMb > 0 && bandwidthUsedMb >= bandwidthLimitMb
    val closed: Boolean get() = diskFull || bandwidthFull
    val aiFull: Boolean get() = maxAiRequests > 0 && aiRequestsUsed >= maxAiRequests
    /** AI search and AI answers can run: the plan has them and the month's allowance is not spent. */
    val aiUsable: Boolean get() = vectorAllowed && !aiFull

    fun toJson(): String = JSONObject()
        .put("plan", plan).put("price", price.toString()).put("recurrence", recurrence)
        .put("vector_allowed", vectorAllowed).put("max_ai_requests", maxAiRequests).put("ai_requests_used", aiRequestsUsed)
        .put("index_limit", indexLimit).put("indexes_used", indexesUsed)
        .put("disk_limit_mb", diskLimitMb).put("disk_used_mb", diskUsedMb)
        .put("bandwidth_limit_mb", bandwidthLimitMb).put("bandwidth_used_mb", bandwidthUsedMb)
        .put("indexed_docs", indexedDocs).put("refreshed_at", refreshedAt)
        .toString()

    companion object {
        fun fromJson(json: JSONObject, previous: AccountLimits? = null): AccountLimits = AccountLimits(
            plan = json.optString("plan", previous?.plan ?: ""),
            price = if (json.has("price")) json.optString("price").replace(",", "").toDoubleOrNull() ?: 0.0 else previous?.price ?: 0.0,
            recurrence = if (json.has("recurrence")) json.optString("recurrence") else previous?.recurrence ?: "",
            vectorAllowed = if (json.has("vector_allowed")) json.optBoolean("vector_allowed") else previous?.vectorAllowed ?: false,
            maxAiRequests = if (json.has("max_ai_requests")) json.optInt("max_ai_requests") else previous?.maxAiRequests ?: 0,
            aiRequestsUsed = if (json.has("ai_requests_used")) json.optInt("ai_requests_used") else previous?.aiRequestsUsed ?: 0,
            indexLimit = if (json.has("index_limit")) json.optInt("index_limit") else previous?.indexLimit ?: 0,
            indexesUsed = if (json.has("indexes_used")) json.optInt("indexes_used") else previous?.indexesUsed ?: 0,
            diskLimitMb = if (json.has("disk_limit_mb")) json.optDouble("disk_limit_mb") else previous?.diskLimitMb ?: 0.0,
            diskUsedMb = if (json.has("disk_used_mb")) json.optDouble("disk_used_mb") else previous?.diskUsedMb ?: 0.0,
            bandwidthLimitMb = if (json.has("bandwidth_limit_mb")) json.optDouble("bandwidth_limit_mb") else previous?.bandwidthLimitMb ?: 0.0,
            bandwidthUsedMb = if (json.has("bandwidth_used_mb")) json.optDouble("bandwidth_used_mb") else previous?.bandwidthUsedMb ?: 0.0,
            indexedDocs = if (json.has("indexed_docs")) json.optLong("indexed_docs") else previous?.indexedDocs ?: 0L,
            refreshedAt = if (json.has("refreshed_at")) json.optLong("refreshed_at") else System.currentTimeMillis(),
        )
    }
}
