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

/**
 * The Opensolr Index of this phone, as in Opensolr Photos: mail_<device id>__dense. Found again after a reinstall
 * (its schema checked, emptied and set up anew when the app ships a newer one), or created with the app's
 * configuration. Every phone has its own; no phone ever writes into another's.
 */
class MailIndex(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)

    /** The phone's own id, the same one Opensolr Photos uses: it survives a reinstall of the app. */
    val deviceId: String get() = prefs.indexId

    /** mail_<device id>__dense: this phone's own index. */
    val ownName: String get() = "mail_" + deviceId + "__dense"

    suspend fun ensure(): IndexConnection = lock.withLock {
        val name = ownName
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

    /** Silent: the region the platform flags as nearest, else the first one the configuration runs on. */
    private suspend fun region(): String {
        val regions = api.vectorRegions()
        if (regions.isEmpty()) throw ServiceException("No region can hold this index")
        regions.firstOrNull { it.nearest }?.let { return it.environment }
        return (regions.firstOrNull { versionAtLeast(it.solrVersion, OpensolrApi.MIN_SOLR) } ?: regions.first()).environment
    }

    private fun versionAtLeast(version: String, min: String): Boolean {
        val have = version.split('.').map { it.toIntOrNull() ?: 0 }
        val need = min.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(have.size, need.size)) {
            val h = have.getOrElse(i) { 0 }
            val n = need.getOrElse(i) { 0 }
            if (h != n) return h > n
        }
        return true
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

        private val lock = Mutex()

        @Volatile
        private var verified: String? = null
    }
}
