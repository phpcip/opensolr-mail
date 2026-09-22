package com.opensolr.mail.dav

import android.content.Context
import android.util.Xml
import com.opensolr.mail.auth.FastmailAuth
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.net.AccountSignInException
import com.opensolr.mail.net.Http
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** WebDAV/CalDAV over the Fastmail OAuth token (Bearer). */
class Dav(private val context: Context, private val account: MailAccount) {

    /** One <response>: its href and the properties that came back 200. Element-valued properties keep their child element names. */
    data class Entry(val href: String, val props: Map<String, String>, val children: Map<String, List<String>>)

    suspend fun propfind(url: String, depth: Int, body: String): List<Entry> = multistatus("PROPFIND", url, depth, body)

    suspend fun report(url: String, depth: Int, body: String): List<Entry> = multistatus("REPORT", url, depth, body)

    /** Stores [ics]; returns the new ETag when the server sends one. */
    suspend fun put(url: String, ics: String, ifMatch: String?, create: Boolean): String? = withContext(Dispatchers.IO) {
        send { token ->
            val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .put(ics.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            if (create) b.header("If-None-Match", "*") else if (!ifMatch.isNullOrEmpty()) b.header("If-Match", ifMatch)
            b.build()
        }.use { r ->
            if (r.code == 412) throw ConflictException()
            if (!r.isSuccessful) throw ServiceException("CalDAV PUT: HTTP ${r.code}")
            r.header("ETag")
        }
    }

    suspend fun delete(url: String, ifMatch: String?) = withContext(Dispatchers.IO) {
        send { token ->
            val b = Request.Builder().url(url).header("Authorization", "Bearer $token").delete()
            if (!ifMatch.isNullOrEmpty()) b.header("If-Match", ifMatch)
            b.build()
        }.use { r ->
            if (r.code == 412) throw ConflictException()
            if (!r.isSuccessful && r.code != 404) throw ServiceException("CalDAV DELETE: HTTP ${r.code}")
        }
    }

    class ConflictException : ServiceException("Changed on the server")

    fun resolve(base: String, href: String): String = base.toHttpUrl().resolve(href)?.toString() ?: href

    private suspend fun multistatus(method: String, url: String, depth: Int, body: String): List<Entry> = withContext(Dispatchers.IO) {
        send { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").header("Depth", depth.toString())
                .method(method, body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        }.use { r ->
            if (r.code != 207) throw ServiceException("CalDAV $method: HTTP ${r.code}")
            parse(r.body?.string().orEmpty())
        }
    }

    private suspend fun send(build: (String) -> Request): Response {
        var r = Http.mail.newCall(build(FastmailAuth.accessToken(context, account.key))).execute()
        if (r.code == 401) {
            r.close()
            r = Http.mail.newCall(build(FastmailAuth.accessToken(context, account.key, force = true))).execute()
            if (r.code == 401) { r.close(); throw AccountSignInException(account.key) }
        }
        return r
    }

    private fun parse(xml: String): List<Entry> {
        val out = ArrayList<Entry>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml))
        var href = ""
        var props = HashMap<String, String>()
        var children = HashMap<String, MutableList<String>>()
        var okStat = false
        var statProps = HashMap<String, String>()
        var statChildren = HashMap<String, MutableList<String>>()
        var depthProp = -1
        var currentProp: String? = null
        var text = StringBuilder()
        var inHref = false
        val path = ArrayList<String>()
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val name = p.name
                    path += name
                    text = StringBuilder()
                    when {
                        name == "response" -> { href = ""; props = HashMap(); children = HashMap() }
                        name == "propstat" -> { okStat = false; statProps = HashMap(); statChildren = HashMap() }
                        name == "prop" -> depthProp = path.size
                        depthProp > 0 && path.size == depthProp + 1 -> currentProp = name
                        depthProp > 0 && path.size > depthProp + 1 && currentProp != null -> {
                            if (name == "href") inHref = true
                            else statChildren.getOrPut(currentProp!!) { ArrayList() }.add(name)
                            if (name == "comp") p.getAttributeValue(null, "name")?.let { statChildren.getOrPut(currentProp!! + ":comp") { ArrayList() }.add(it) }
                        }
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val name = p.name
                    when {
                        name == "href" && currentProp == null && path.size >= 2 && path[path.size - 2] == "response" -> href = text.toString().trim()
                        name == "href" && inHref && currentProp != null -> {
                            statProps[currentProp!!] = text.toString().trim()
                            inHref = false
                        }
                        name == "status" && path.size >= 2 && path[path.size - 2] == "propstat" -> okStat = text.contains(" 200")
                        depthProp > 0 && path.size == depthProp + 1 && name == currentProp -> {
                            if (!statProps.containsKey(name)) statProps[name] = text.toString().trim()
                            currentProp = null
                        }
                        name == "prop" -> depthProp = -1
                        name == "propstat" -> if (okStat) { props.putAll(statProps); statChildren.forEach { (k, v) -> children.getOrPut(k) { ArrayList() }.addAll(v) } }
                        name == "response" -> out += Entry(href, props, children)
                    }
                    path.removeAt(path.size - 1)
                    text = StringBuilder()
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }
}
