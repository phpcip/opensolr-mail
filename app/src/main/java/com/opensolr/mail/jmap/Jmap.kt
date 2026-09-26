package com.opensolr.mail.jmap

import android.content.Context
import com.opensolr.mail.auth.FastmailAuth
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.net.AccountSignInException
import com.opensolr.mail.net.Http
import com.opensolr.mail.net.RateLimitedException
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JMAP (RFC 8620/8621) against one Fastmail account. */
class Jmap(private val context: Context, val account: MailAccount) {

    val accountId: String get() = account.jmapAccountId

    /** Collects method calls for one request; each call's id is its position. */
    class Batch(val using: List<String> = MAIL) {
        internal val calls = JSONArray()
        fun add(method: String, args: JSONObject): String {
            val id = "c${calls.length()}"
            calls.put(JSONArray().put(method).put(args).put(id))
            return id
        }
    }

    /** The answers of one request, by call id. A method error surfaces as a [JmapError] when read. */
    class Result(private val byId: Map<String, Pair<String, JSONObject>>) {
        fun get(id: String): JSONObject {
            val (name, args) = byId[id] ?: throw ServiceException("JMAP: no answer for $id")
            if (name == "error") throw JmapError(args.optString("type"), args.optString("description"))
            return args
        }

        fun opt(id: String): JSONObject? = byId[id]?.takeIf { it.first != "error" }?.second
    }

    class JmapError(val type: String, description: String) : ServiceException("JMAP $type $description")

    suspend fun send(batch: Batch): Result = withContext(Dispatchers.IO) {
        val body = JSONObject().put("using", JSONArray(batch.using)).put("methodCalls", batch.calls).toString()
        var response = post(body, force = false)
        if (response.code == 401) {
            response.close()
            response = post(body, force = true)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 -> throw AccountSignInException(account.key)
                r.code == 429 -> throw RateLimitedException(r.header("Retry-After")?.toLongOrNull() ?: 30)
                !r.isSuccessful -> throw ServiceException("Fastmail answered HTTP ${r.code}")
            }
            val responses = JSONObject(text).getJSONArray("methodResponses")
            val map = HashMap<String, Pair<String, JSONObject>>()
            for (i in 0 until responses.length()) {
                val r0 = responses.getJSONArray(i)
                map[r0.getString(2)] = r0.getString(0) to r0.getJSONObject(1)
            }
            Result(map)
        }
    }

    /** One method, one answer. */
    suspend fun call(method: String, args: JSONObject, using: List<String> = MAIL): JSONObject {
        val b = Batch(using)
        val id = b.add(method, args.put("accountId", accountId))
        return send(b).get(id)
    }

    private suspend fun post(body: String, force: Boolean): Response {
        val token = FastmailAuth.accessToken(context, account.key, force)
        val req = Request.Builder().url(account.apiUrl)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON))
            .build()
        return Http.mail.newCall(req).execute()
    }

    /** The address a blob is downloaded from. */
    fun downloadUrlOf(blobId: String, name: String, type: String): String = account.downloadUrl
        .replace("{accountId}", enc(accountId))
        .replace("{blobId}", enc(blobId))
        .replace("{name}", enc(name.ifBlank { "file" }))
        .replace("{type}", enc(type.ifBlank { "application/octet-stream" }))

    /** Downloads a blob to [target]. */
    suspend fun download(blobId: String, name: String, type: String, target: File) = withContext(Dispatchers.IO) {
        val url = downloadUrlOf(blobId, name, type)
        suspend fun get(force: Boolean): Response {
            val token = FastmailAuth.accessToken(context, account.key, force)
            return Http.mail.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute()
        }
        var r = get(false)
        if (r.code == 401) { r.close(); r = get(true) }
        r.use {
            if (!it.isSuccessful) throw ServiceException("Download failed: HTTP ${it.code}")
            // Written beside the target and moved into place whole: a cut download never passes for a finished file.
            val part = File(target.path + ".part")
            try {
                it.body!!.byteStream().use { input -> part.outputStream().use { out -> input.copyTo(out) } }
                if (!part.renameTo(target)) throw java.io.IOException("Download could not be saved")
            } finally {
                part.delete()
            }
        }
    }

    /** Uploads bytes, returns the blob id. */
    suspend fun upload(bytes: ByteArray, type: String): String = withContext(Dispatchers.IO) {
        val url = account.uploadUrl.replace("{accountId}", enc(accountId))
        suspend fun put(force: Boolean): Response {
            val token = FastmailAuth.accessToken(context, account.key, force)
            return Http.mail.newCall(
                Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .post(bytes.toRequestBody(type.ifBlank { "application/octet-stream" }.toMediaType())).build()
            ).execute()
        }
        var r = put(false)
        if (r.code == 401) { r.close(); r = put(true) }
        r.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ServiceException("Upload failed: HTTP ${it.code}")
            JSONObject(text).getString("blobId")
        }
    }

    companion object {
        const val CORE = "urn:ietf:params:jmap:core"
        const val MAIL_CAP = "urn:ietf:params:jmap:mail"
        const val SUBMISSION = "urn:ietf:params:jmap:submission"
        val MAIL = listOf(CORE, MAIL_CAP)
        val SEND = listOf(CORE, MAIL_CAP, SUBMISSION)
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** The header properties every list and notification needs. */
        val HEADER_PROPS = JSONArray(
            listOf(
                "id", "threadId", "mailboxIds", "keywords", "receivedAt", "sentAt", "subject", "from", "to", "cc",
                "bcc", "replyTo", "preview", "hasAttachment", "size", "messageId", "inReplyTo", "references",
            )
        )
    }
}
