package com.opensolr.mail.chat

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.index.MailIndex
import com.opensolr.mail.index.MailIndexer
import com.opensolr.mail.net.Http
import com.opensolr.mail.net.OpensolrApi
import com.opensolr.mail.net.QuotaExceededException
import com.opensolr.mail.net.SignInRequiredException
import com.opensolr.mail.net.VectorNotAllowedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The mail chat: one question to mail_chat on api.opensolr.com, where the model searches the reader's own index,
 * and the answer streamed back as events (status, tool, mails, text, done, error).
 */
class MailChat(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val store = AccountStore.get(context)

    sealed class Event {
        data class Status(val stage: String) : Event()
        data class Tool(val name: String, val args: Map<String, String>) : Event()
        /** Mails a tool found: each one's index id, its conversation, its attachments (their links number them from 1) and its subject. */
        data class Mails(val items: List<MailRef>) : Event()
        data class Text(val text: String) : Event()
    }

    data class MailRef(val id: String, val thread: String, val files: List<String>, val subject: String)

    /** Raised for an error event or a stream that stalled or ran too long; [code] is the server's code. */
    class ChatException(val code: String) : Exception(code)

    /** Streams the answer to the last of [messages] (role to text, the reader's question last) into [onEvent]. */
    suspend fun ask(messages: List<Pair<String, String>>, onEvent: (Event) -> Unit) = withContext(Dispatchers.IO) {
        val connection = MailIndex(context).ensure()
        val accounts = store.all()
        val body = JSONObject()
            .put("email", prefs.email).put("api_key", prefs.apiKey).put("index_name", connection.indexName)
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.first).put("content", it.second) }))
            .put("accounts", JSONArray(accounts.map { MailIndexer.indexKey(it) }.distinct()))
            .put("addresses", JSONArray(accounts.map { it.username }.distinct()))
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("instructions", prefs.aiInstructions)
            .put("alpha", (1f - prefs.lexicalWeight).toDouble())
            .put("match", prefs.matchLevel)
        val url = (OpensolrApi.AI + "mail_chat").toHttpUrl().newBuilder().addQueryParameter("index_name", connection.indexName).build()
        val call = Http.stream.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build())
        // Stopping the chat (New chat, leaving the app) closes the connection at once, so the server stops too.
        val stop = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        // No byte for STALL_MS, or the whole answer longer than TOTAL_MS: the call is cut and the reader told.
        val started = System.currentTimeMillis()
        var last = started
        var ended = false
        val watchdog = java.util.Timer(true)
        watchdog.schedule(object : java.util.TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                if (now - last > STALL_MS || now - started > TOTAL_MS) call.cancel()
            }
        }, 1000, 1000)
        try {
            call.execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                if (!type.startsWith("text/event-stream")) {
                    val text = r.body?.string().orEmpty()
                    if (text.contains("ERROR_AUTHENTICATION_FAILED")) throw SignInRequiredException()
                    if (text.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
                    if (text.contains("ERROR_AI_MONTHLY_QUOTA_EXCEEDED")) throw QuotaExceededException(runCatching { JSONObject(text).optString("resets_at") }.getOrNull())
                    val msg = runCatching { JSONObject(text).optString("msg") }.getOrNull().orEmpty()
                    throw ChatException(msg.ifBlank { "HTTP ${r.code}" })
                }
                val source = r.body?.source() ?: throw ChatException("ERROR_NO_ANSWER")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    last = System.currentTimeMillis()
                    if (!line.startsWith("data:")) continue
                    val e = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                    when (e.optString("type")) {
                        "status" -> onEvent(Event.Status(e.optString("stage")))
                        "tool" -> {
                            val a = e.optJSONObject("args") ?: JSONObject()
                            onEvent(Event.Tool(e.optString("name"), a.keys().asSequence().associateWith { a.optString(it) }))
                        }
                        "mails" -> {
                            val items = e.optJSONArray("items") ?: JSONArray()
                            onEvent(Event.Mails((0 until items.length()).mapNotNull { i ->
                                items.optJSONArray(i)?.let { a -> MailRef(a.optString(0), a.optString(1), a.optJSONArray(2)?.let { n -> (0 until n.length()).map { n.optString(it) } }.orEmpty(), a.optString(3)) }
                            }))
                        }
                        "text" -> onEvent(Event.Text(e.optString("text")))
                        "done" -> { ended = true; return@use }
                        "error" -> throw ChatException(e.optString("msg").ifBlank { "ERROR_NO_ANSWER" })
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (e is SignInRequiredException || e is VectorNotAllowedException || e is QuotaExceededException) throw e
            // A cut connection is the stall or the time limit when the chat itself was not stopped.
            if (coroutineContext[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException()
            throw ChatException(if (System.currentTimeMillis() - last > STALL_MS || System.currentTimeMillis() - started > TOTAL_MS) "ERROR_ASSISTANT_TIMEOUT" else "ERROR_NETWORK")
        } finally {
            watchdog.cancel()
            stop?.dispose()
        }
        if (!ended) throw ChatException("ERROR_NO_ANSWER")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** The server gives up after 60 seconds (a translation after 180); the phone a little later. */
        const val STALL_MS = 30_000L
        const val TOTAL_MS = 200_000L
        /** The reader's question, as the server takes it. */
        const val MAX_CHARS = 4000
        /** Questions in one chat; the next one starts a new chat. */
        const val MAX_QUESTIONS = 50
        /** Earlier messages sent with each question: the server takes 20 and the question. */
        const val HISTORY = 20
    }
}
