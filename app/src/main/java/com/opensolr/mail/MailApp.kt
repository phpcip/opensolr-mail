package com.opensolr.mail

import android.app.Application
import com.opensolr.mail.sync.Notifier

class MailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        Notifier.ensureChannels(this)
    }
}
