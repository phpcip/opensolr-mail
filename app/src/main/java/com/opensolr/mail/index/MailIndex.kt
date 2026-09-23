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
import java.util.TimeZone

/**
 * The Opensolr Index of this phone, as in Opensolr Photos: mail_<device id>__dense. Found again after a reinstall
 * (its schema checked, emptied and set up anew when the app ships a newer one), or created with the app's
 * configuration. Every phone has its own; no phone ever writes into another's.
 */
class MailIndex(private val context: Context) {

    private val prefs = AppPrefs(context)
    private val api = OpensolrApi(prefs)

    /** The phone's own id, the same one Opensolr Photos uses: it survives a reinstall of the app. */
    val deviceId: String
        @android.annotation.SuppressLint("HardwareIds")
        get() = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            .orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(32)
            .ifEmpty { prefs.deviceId.lowercase().filter { it in 'a'..'f' || it in '0'..'9' }.take(32) }

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
