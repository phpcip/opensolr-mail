package com.opensolr.mail.index

import com.opensolr.mail.net.Http
import com.opensolr.mail.net.IndexConnection
import com.opensolr.mail.net.IndexMissingException
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Straight to the mail index, with its own HTTP credentials. Every parameter travels in a POST body.
 * Opensolr changes those credentials every night: a 401 fetches the new pair once and repeats the request.
 */
class SolrClient(connection: IndexConnection) {

    @Volatile
    private var c = connection

    suspend fun select(params: List<Pair<String, String>>): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(send { base, auth ->
            val form = FormBody.Builder().apply { params.forEach { add(it.first, it.second) } }.add("wt", "json").build()
            Request.Builder().url("$base/select").header("Authorization", auth).post(form).build()
        })
    }

    /** [commitWithinMs] is how long the index may wait before the write becomes searchable. */
    suspend fun add(docs: JSONArray, commitWithinMs: Int = DEFAULT_COMMIT_MS) = update(docs.toString(), commitWithinMs)

    suspend fun deleteIds(ids: Collection<String>, commitWithinMs: Int = DEFAULT_COMMIT_MS) {
        if (ids.isEmpty()) return
        update(JSONObject().put("delete", JSONArray(ids)).toString(), commitWithinMs)
    }

    suspend fun deleteQuery(q: String, commitWithinMs: Int = DEFAULT_COMMIT_MS) = update(JSONObject().put("delete", JSONObject().put("query", q)).toString(), commitWithinMs)

    /** Empties the index and commits at once. */
    suspend fun resetAll() = withContext(Dispatchers.IO) {
        send { base, auth ->
            Request.Builder().url("$base/update?commit=true&wt=json").header("Authorization", auth)
                .post("{\"delete\":{\"query\":\"*:*\"}}".toRequestBody(JSON)).build()
        }
        Unit
    }

    private suspend fun update(body: String, commitWithinMs: Int = DEFAULT_COMMIT_MS) = withContext(Dispatchers.IO) {
        send { base, auth ->
            Request.Builder().url("$base/update?commitWithin=$commitWithinMs&wt=json").header("Authorization", auth)
                .post(body.toRequestBody(JSON)).build()
        }
        Unit
    }

    /** config_version of the configuration the index runs, 0 when it does not answer it. */
    suspend fun configVersion(): Int = withContext(Dispatchers.IO) {
        val (code, raw) = sendRaw { base, auth -> Request.Builder().url("$base/opensolr-mail-config?wt=json").header("Authorization", auth).build() }
        if (code == 404) return@withContext 0
        val text = check(code, raw)
        JSONObject(text).optJSONObject("responseHeader")?.optJSONObject("params")?.optString("config_version")?.toIntOrNull() ?: 0
    }

    /** One request; on 401 the index's current credentials are fetched and the request is made once more. */
    private suspend fun send(build: (String, String) -> Request): String = sendRaw(build).let { check(it.first, it.second) }

    private suspend fun sendRaw(build: (String, String) -> Request): Pair<Int, String> {
        val first = c
        val answer = execute(build(first.baseUrl.trimEnd('/'), auth(first)))
        if (answer.first != 401) return answer
        val fresh = refreshed(first) ?: return answer
        c = fresh
        return execute(build(fresh.baseUrl.trimEnd('/'), auth(fresh)))
    }

    private fun execute(req: Request): Pair<Int, String> =
        Http.client.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }

    private fun auth(k: IndexConnection) = Credentials.basic(k.username, k.password, Charsets.UTF_8)

    private fun check(code: Int, text: String): String {
        when {
            code == 401 || code == 403 -> throw IndexMissingException()
            code == 404 -> throw IndexMissingException()
            code >= 400 -> throw ServiceException("Solr answered HTTP $code: " + text.take(200))
        }
        return text
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_COMMIT_MS = 5_000
        private val refreshLock = Mutex()

        /** Set by MailApp: the index's current connection from Opensolr, saved for every later client. */
        @Volatile
        var fetchConnection: (suspend (String) -> IndexConnection?)? = null

        /** Many requests fail together at a rotation: only the first asks Opensolr, the rest reuse its answer. */
        private suspend fun refreshed(stale: IndexConnection): IndexConnection? = refreshLock.withLock {
            latest?.takeIf { it.indexName == stale.indexName && it != stale && System.currentTimeMillis() - latestAt < REUSE_MS }?.let { return@withLock it }
            val fresh = try {
                fetchConnection?.invoke(stale.indexName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@withLock null
            if (fresh == stale) return@withLock null
            latest = fresh
            latestAt = System.currentTimeMillis()
            fresh
        }

        @Volatile
        private var latest: IndexConnection? = null

        @Volatile
        private var latestAt = 0L

        private const val REUSE_MS = 60_000L
    }
}
