package com.opensolr.mail

import android.app.Application
import com.opensolr.mail.sync.Notifier

class MailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        com.opensolr.mail.ui.Haptics.enabled = com.opensolr.mail.data.AppPrefs(this).haptics
        Notifier.ensureChannels(this)
    }
}
