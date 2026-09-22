package com.opensolr.mail.index

import android.content.Context
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.net.IndexConnection
import com.opensolr.mail.net.IndexMissingException
import com.opensolr.mail.net.OpensolrApi
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.TimeZone

/** The one Opensolr Index that holds the mail of this Opensolr account, found or created, with the configuration the app ships. */
class MailIndex(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)

    /** mail_<16 hex of the account email>__dense: every phone of the same account finds the same index. */
    fun nameFor(email: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase().toByteArray())
        return "mail_" + d.take(8).joinToString("") { "%02x".format(it) } + "__dense"
    }

    suspend fun ensure(): IndexConnection = lock.withLock {
        val name = nameFor(prefs.email)
        IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == name && verified == name }?.let { return@withLock it }

        val exists = api.indexNames().contains(name)
        if (!exists) {
            prefs.connectionJson = ""
            api.createIndex(name, region())
        }
        val connection = connectionWithRetry(name)
        val solr = SolrClient(connection)
        val version = runCatching { solr.configVersion() }.getOrDefault(0)
        if (version < CONFIG_VERSION) {
            // An index built under an older configuration is emptied FIRST, then the new one goes in, then everything is indexed again.
            if (exists && version > 0) {
                solr.resetAll()
                context.getSharedPreferences("index_status", Context.MODE_PRIVATE).edit().putBoolean("reindex_all", true).commit()
            }
            api.uploadConfig(name, context.assets.open(CONFIG_ASSET).use { it.readBytes() })
            waitForConfig(solr)
        }
        prefs.indexName = name
        prefs.connectionJson = connection.toJson()
        verified = name
        connection
    }

    fun forget() {
        verified = null
        prefs.connectionJson = ""
    }

    private suspend fun region(): String {
        val regions = api.vectorRegions()
        if (regions.isEmpty()) throw ServiceException("No region can hold this index")
        val americas = TimeZone.getDefault().id.startsWith("America/")
        val wanted = if (americas) REGION_AMERICAS else REGION_EUROPE
        return regions.firstOrNull { it.first.equals(wanted, true) }?.first
            ?: regions.firstOrNull { (it.second == "USA") == americas }?.first
            ?: regions.first().first
    }

    private suspend fun connectionWithRetry(name: String): IndexConnection {
        var last: Exception? = null
        repeat(6) { attempt ->
            try {
                return api.connection(name)
            } catch (e: IndexMissingException) {
                last = e
            } catch (e: ServiceException) {
                last = e
            }
            delay(2000L * (attempt + 1))
        }
        throw last ?: ServiceException("The index is not reachable")
    }

    private suspend fun waitForConfig(solr: SolrClient) {
        repeat(15) {
            if (runCatching { solr.configVersion() }.getOrDefault(0) == CONFIG_VERSION) return
            delay(3000)
        }
        throw ServiceException("The index configuration did not go live")
    }

    companion object {
        const val CONFIG_ASSET = "opensolr-mail-conf.zip"
        const val CONFIG_VERSION = 3
        const val REGION_AMERICAS = "CHICAGO-96"
        const val REGION_EUROPE = "FINLAND9"

        private val lock = Mutex()

        @Volatile
        private var verified: String? = null
    }
}
