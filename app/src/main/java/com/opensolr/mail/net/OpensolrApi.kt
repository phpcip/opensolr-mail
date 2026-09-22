package com.opensolr.mail.net

import com.opensolr.mail.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class IndexConnection(val indexName: String, val baseUrl: String, val username: String, val password: String) {
    fun toJson(): String = JSONObject().put("name", indexName).put("url", baseUrl).put("user", username).put("pass", password).toString()

    companion object {
        fun fromJson(s: String): IndexConnection? = runCatching {
            val o = JSONObject(s)
            IndexConnection(o.getString("name"), o.getString("url"), o.getString("user"), o.getString("pass"))
        }.getOrNull()?.takeIf { it.baseUrl.startsWith("https://") }
    }
}


/** The Opensolr calls the app makes, all authenticated with the account email and API key. */
class OpensolrApi(private val prefs: AppPrefs) {

    private val email get() = prefs.email
    private val key get() = prefs.apiKey

    // ---------- the app's own surface ----------

    suspend fun session(version: String, label: String, pushToken: String?) = appCall(
        "session",
        JSONObject().put("device_id", prefs.deviceId).put("device_label", label).put("app_version", version)
            .apply { if (pushToken != null) put("push_token", pushToken) },
    )

    /** A fresh relay address for one mail account on this phone. */
    suspend fun pushRegister(accountKey: String): String =
        appCall("push_register", JSONObject().put("device_id", prefs.deviceId).put("account_key", accountKey))
            .optJSONObject("msg")?.optString("url")?.takeIf { it.startsWith("https://opensolr.com/app/mail/push/") }
            ?: throw ServiceException("No relay address")

    suspend fun pushUnregister(accountKey: String?) {
        appCall("push_unregister", JSONObject().put("device_id", prefs.deviceId).apply { if (accountKey != null) put("account_key", accountKey) })
    }

    private suspend fun appCall(method: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        body.put("email", email).put("api_key", key)
        val req = Request.Builder().url("$SITE/app/mail/api/$method").post(body.toString().toRequestBody(JSON)).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || text.contains("ERROR_AUTHENTICATION_FAILED")) throw SignInRequiredException()
            if (r.code == 429) throw RateLimitedException(60)
            val json = runCatching { JSONObject(text) }.getOrElse { throw ServiceException("Opensolr answered HTTP ${r.code}") }
            if (!json.optBoolean("status")) throw ServiceException(json.optString("msg"))
            json
        }
    }

    // ---------- index management ----------

    /** Names of the account's indexes. */
    suspend fun indexNames(): List<String> = withContext(Dispatchers.IO) {
        val t = post(MANAGEMENT + "get_index_list", form()).trim()
        if (!t.startsWith("[")) throw ServiceException(message(t))
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("index_name")?.takeIf { n -> n.isNotBlank() } }
    }

    suspend fun vectorRegions(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val t = post(MANAGEMENT + "vector_regions", form()).trim()
        if (!t.startsWith("[")) throw ServiceException(message(t))
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("environment") to it.optString("country") }
            .filter { it.first.isNotBlank() }
    }

    suspend fun createIndex(name: String, region: String) = withContext(Dispatchers.IO) {
        val url = (MANAGEMENT + "create_index").toHttpUrl().newBuilder()
            .addQueryParameter("core_name", name).addQueryParameter("region", region).build()
        val json = obj(post(url.toString(), form { add("core_name", name) }))
        if (!json.optBoolean("status")) {
            val msg = json.optString("msg")
            if (msg.startsWith("ERROR_CANNOT_ADD_MORE_THAN_")) throw IndexLimitException()
            throw ServiceException(message(json.toString()))
        }
    }

    suspend fun uploadConfig(name: String, zip: ByteArray) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("email", email)
            .addFormDataPart("api_key", key)
            .addFormDataPart("core_name", name)
            .addFormDataPart("userfile", "opensolr-mail-conf.zip", zip.toRequestBody("application/zip".toMediaType()))
            .build()
        val text = execute(Request.Builder().url(MANAGEMENT + "upload_zip_config_files").post(body).build())
        val start = text.indexOf('{')
        val json = if (start >= 0) obj(text.substring(start)) else JSONObject()
        if (!json.optBoolean("status")) throw ServiceException(message(text))
    }

    suspend fun connection(name: String): IndexConnection = withContext(Dispatchers.IO) {
        val url = (MANAGEMENT + "get_core_info").toHttpUrl().newBuilder().addQueryParameter("core_name", name).build()
        val json = obj(post(url.toString(), form { add("core_name", name) }))
        if (!json.optBoolean("status")) {
            if (json.optString("msg") == "NOT_OWNER_ERROR") throw IndexMissingException()
            throw ServiceException(message(json.toString()))
        }
        val info = json.optJSONObject("msg")?.optJSONObject("info") ?: throw ServiceException("No connection details")
        val base = info.optString("connection_url")
        if (!base.startsWith("https://")) throw ServiceException("The index has no HTTPS address")
        IndexConnection(name, base, info.optString("auth_username"), info.optString("auth_password"))
    }

    /** The plan's limits and their use, signed with the API key over the index name and the email. */
    suspend fun accountSummary(name: String, previous: com.opensolr.mail.data.AccountLimits?): com.opensolr.mail.data.AccountLimits = withContext(Dispatchers.IO) {
        val json = obj(post(MANAGEMENT + "get_account_summary", form {
            add("core_name", name)
            add("signature", hmac(key, name + email))
        }))
        if (!json.optBoolean("status")) throw ServiceException(message(json.toString()))
        com.opensolr.mail.data.AccountLimits.fromJson(json.optJSONObject("msg") ?: JSONObject(), previous)
    }

    // ---------- AI ----------

    suspend fun batchEmbed(name: String, texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name).put("payloads", JSONArray(texts))
        val text = execute(Request.Builder().url(AI + "batch_embed").post(body.toString().toRequestBody(JSON)).build())
        val json = obj(text)
        if (json.optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        val vectors = json.optJSONArray("embeddings") ?: throw ServiceException(json.optString("error").ifBlank { message(text) })
        if (vectors.length() != texts.size) throw ServiceException("Embedding count mismatch")
        (0 until vectors.length()).map { floats(vectors.getJSONArray(it)) }
    }

    suspend fun embedQuery(name: String, query: String): FloatArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name).put("is_query", "1").put("payload", query)
        val text = execute(Request.Builder().url(AI + "embed").post(body.toString().toRequestBody(JSON)).build()).trim()
        if (text.startsWith("[")) return@withContext floats(JSONArray(text))
        if (obj(text).optString("msg") == "VECTOR_NOT_ALLOWED") throw VectorNotAllowedException()
        throw ServiceException(message(text))
    }

    /** The text printed in up to 5 pictures (tesseract on api.opensolr.com); null per picture that had none or failed. */
    suspend fun imageOcr(name: String, jpegs: List<ByteArray>): List<String?> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name)
            .put("images", JSONArray(jpegs.map { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }))
        val text = execute(Request.Builder().url(AI + "image_ocr").post(body.toString().toRequestBody(JSON)).build())
        val json = obj(text)
        val results = json.optJSONArray("results") ?: throw ServiceException(message(text))
        (0 until jpegs.size).map { i -> results.optJSONObject(i)?.takeIf { it.optBoolean("status") }?.optString("text")?.takeIf { it.isNotBlank() } }
    }

    /** The text of up to 5 documents sent as files (doc_to_text); null per document with no text. Throws [EndpointMissingException] where the endpoint is not live yet. */
    suspend fun docToText(name: String, docs: List<Pair<String, ByteArray>>): List<String?> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("api_key", key).put("index_name", name)
            .put("documents", JSONArray(docs.map { (n, b) -> JSONObject().put("name", n).put("data", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)) }))
        val req = Request.Builder().url(AI + "doc_to_text").post(body.toString().toRequestBody(JSON)).build()
        Http.stream.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            classify(r.code, text, r.header("Retry-After"))
            if (r.code == 404 || r.code == 403) throw EndpointMissingException()
            if (r.code >= 500) throw ServiceException("Opensolr answered HTTP ${r.code}")
            val results = obj(text).optJSONArray("results") ?: throw ServiceException(message(text))
            (0 until docs.size).map { i -> results.optJSONObject(i)?.takeIf { it.optBoolean("status") }?.optString("text")?.takeIf { it.isNotBlank() } }
        }
    }

    /** Streams the AI answer for [instruction] (the whole prompt), chunk by chunk. */
    suspend fun aiAnswer(name: String, instruction: String, onChunk: (String) -> Unit) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("email", email).add("api_key", key).add("index_name", name)
            .add("instruction", instruction).add("temperature", "0.1").add("stream", "yes")
            .build()
        val req = Request.Builder().url(AI + "ai_summary").post(form).build()
        Http.stream.newCall(req).execute().use { r ->
            classify(r.code, "", r.header("Retry-After"))
            if (!r.isSuccessful) throw ServiceException("HTTP ${r.code}")
            val reader = r.body?.charStream() ?: return@use
            val buf = CharArray(2048)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                if (chunk.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
                onChunk(chunk)
            }
        }
    }

    // ---------- plumbing ----------

    private fun form(extra: FormBody.Builder.() -> Unit = {}): FormBody =
        FormBody.Builder().add("email", email).add("api_key", key).apply(extra).build()

    private fun post(url: String, body: FormBody): String = execute(Request.Builder().url(url).post(body).build())

    private fun execute(request: Request): String = Http.client.newCall(request).execute().use { r ->
        val text = r.body?.string().orEmpty()
        classify(r.code, text, r.header("Retry-After"))
        if (r.code >= 500) throw ServiceException("Opensolr answered HTTP ${r.code}")
        text
    }

    private fun classify(code: Int, text: String, retryAfter: String?) {
        if (text.contains("ERROR_AUTHENTICATION_FAILED")) throw SignInRequiredException()
        if (text.contains("VECTOR_NOT_ALLOWED")) throw VectorNotAllowedException()
        if (code == 429) {
            if (text.contains("ERROR_AI_MONTHLY_QUOTA_EXCEEDED")) throw QuotaExceededException(runCatching { JSONObject(text).optString("resets_at") }.getOrNull())
            throw RateLimitedException(retryAfter?.toLongOrNull() ?: 60L)
        }
    }

    private fun obj(text: String): JSONObject = runCatching { JSONObject(text.trim()) }.getOrElse { JSONObject() }

    private fun message(text: String): String {
        val o = obj(text)
        return when (val m = o.opt("msg")) {
            is String -> m
            is JSONObject -> m.optString("ERROR", m.toString())
            else -> text.take(160).ifBlank { "empty answer" }
        }
    }

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    private fun hmac(k: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(k.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SITE = "https://opensolr.com"
        const val MANAGEMENT = "https://opensolr.com/solr_manager/api/"
        const val AI = "https://api.opensolr.com/solr_manager/api/"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
