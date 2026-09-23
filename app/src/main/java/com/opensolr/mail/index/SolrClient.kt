package com.opensolr.mail.index

import com.opensolr.mail.net.Http
import com.opensolr.mail.net.IndexConnection
import com.opensolr.mail.net.IndexMissingException
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Straight to the mail index, with its own HTTP credentials. Every parameter travels in a POST body. */
class SolrClient(private val c: IndexConnection) {

    private val base = c.baseUrl.trimEnd('/')
    private val auth = Credentials.basic(c.username, c.password, Charsets.UTF_8)

    suspend fun select(params: List<Pair<String, String>>): JSONObject = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { params.forEach { add(it.first, it.second) } }.add("wt", "json").build()
        val req = Request.Builder().url("$base/select").header("Authorization", auth).post(form).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            check(r.code, text)
            JSONObject(text)
        }
    }

    /** [commitWithinMs] is how long the index may wait before the write becomes searchable. */
    suspend fun add(docs: JSONArray, commitWithinMs: Int = DEFAULT_COMMIT_MS) = update(docs.toString(), commitWithinMs)

    suspend fun deleteIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        update(JSONObject().put("delete", JSONArray(ids)).toString())
    }

    suspend fun deleteQuery(q: String) = update(JSONObject().put("delete", JSONObject().put("query", q)).toString())

    /** Empties the index and commits at once. */
    suspend fun resetAll() = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/update?commit=true&wt=json").header("Authorization", auth)
            .post("{\"delete\":{\"query\":\"*:*\"}}".toRequestBody(JSON)).build()
        Http.client.newCall(req).execute().use { r -> check(r.code, r.body?.string().orEmpty()) }
    }

    private suspend fun update(body: String, commitWithinMs: Int = DEFAULT_COMMIT_MS) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/update?commitWithin=$commitWithinMs&wt=json").header("Authorization", auth)
            .post(body.toRequestBody(JSON)).build()
        Http.client.newCall(req).execute().use { r -> check(r.code, r.body?.string().orEmpty()) }
    }

    /** config_version of the configuration the index runs, 0 when it does not answer it. */
    suspend fun configVersion(): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/opensolr-mail-config?wt=json").header("Authorization", auth).build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext 0
            val text = r.body?.string().orEmpty()
            check(r.code, text)
            JSONObject(text).optJSONObject("responseHeader")?.optJSONObject("params")?.optString("config_version")?.toIntOrNull() ?: 0
        }
    }

    private fun check(code: Int, text: String) {
        when {
            code == 401 || code == 403 -> throw IndexMissingException()
            code == 404 -> throw IndexMissingException()
            code >= 400 -> throw ServiceException("Solr answered HTTP $code: " + text.take(200))
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_COMMIT_MS = 5_000
    }
}
