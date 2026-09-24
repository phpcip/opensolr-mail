package com.opensolr.mail

import android.app.Application
import com.opensolr.mail.sync.Notifier

class MailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        com.opensolr.mail.ui.Haptics.enabled = com.opensolr.mail.data.AppPrefs(this).haptics
        Notifier.ensureChannels(this)
        com.opensolr.mail.index.SolrClient.fetchConnection = { name ->
            val prefs = com.opensolr.mail.data.AppPrefs(this)
            com.opensolr.mail.net.OpensolrApi(prefs).connection(name).also { fresh ->
                if (com.opensolr.mail.net.IndexConnection.fromJson(prefs.connectionJson)?.indexName == name) prefs.connectionJson = fresh.toJson()
            }
        }
    }
}
