package com.opensolr.mail.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.opensolr.mail.AppText
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.sync.Work
import kotlinx.coroutines.runBlocking

/** FCM: the relay's wake-ups for new mail and the verification code of a new subscription. */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        AppText.init(this)
        runCatching { runBlocking { MailPush.ensure(this@PushService, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        AppText.init(this)
        val d = message.data
        when (d["type"]) {
            "mail_push" -> {
                val key = d["key"].orEmpty()
                val body = runCatching { android.util.Base64.decode(d["body"].orEmpty(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP) }.getOrNull()
                if (AccountStore.get(this).get(key) != null && body != null) {
                    runCatching { runBlocking { MailPush.onPush(this@PushService, key, body) } }
                }
            }
            "mail_state" -> {
                val key = d["key"].orEmpty()
                if (AccountStore.get(this).get(key) != null) Work.syncNow(this)
            }
            "mail_verify" -> {
                val key = d["key"].orEmpty()
                val sub = d["sub"].orEmpty()
                val code = d["code"].orEmpty()
                if (key.isNotEmpty() && sub.isNotEmpty() && code.isNotEmpty()) {
                    runCatching { runBlocking { MailPush.verify(this@PushService, key, sub, code) } }
                }
            }
        }
    }
}
