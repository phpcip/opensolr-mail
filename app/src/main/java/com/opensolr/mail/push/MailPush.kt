package com.opensolr.mail.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.opensolr.mail.BuildConfig
import com.opensolr.mail.auth.Pkce
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.jmap.Jmap
import com.opensolr.mail.jmap.MailSync
import com.opensolr.mail.net.OpensolrApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Push for new mail: Fastmail calls a relay address on opensolr.com (a JMAP PushSubscription), the
 * relay wakes this phone through FCM, the phone syncs. Only "something changed" travels; never mail.
 */
object MailPush {

    private const val RENEW_BEFORE = 2 * 86_400_000L

    /** Registers the phone with opensolr.com and makes sure every account has a live subscription. */
    suspend fun ensure(context: Context, token: String? = null) {
        val prefs = AppPrefs(context)
        if (!prefs.signedIn || com.google.firebase.FirebaseApp.getApps(context).isEmpty()) return
        val fcm = token ?: FirebaseMessaging.getInstance().token.await()
        val sp = context.getSharedPreferences("push", Context.MODE_PRIVATE)
        val store = AccountStore.get(context)
        val now = System.currentTimeMillis()
        val needs = store.all().filter {
            it.pushSubscriptionId.isEmpty() || it.pushExpires - now < RENEW_BEFORE || (!it.pushVerified && now - it.pushCreated > 10 * 60_000L)
        }
        val due = needs.isNotEmpty() || sp.getString("token", null) != fcm || System.currentTimeMillis() - sp.getLong("at", 0L) > 86_400_000L
        val api = OpensolrApi(prefs)
        if (due) {
            api.session(BuildConfig.VERSION_NAME, Pkce.deviceLabel(), fcm)
            sp.edit().putString("token", fcm).putLong("at", System.currentTimeMillis()).apply()
        }
        needs.forEach { a -> runCatching { subscribe(context, a, api) }.onFailure { android.util.Log.w("MailPush", "subscribe failed", it) } }
    }

    private suspend fun subscribe(context: Context, a: MailAccount, api: OpensolrApi) {
        val jmap = Jmap(context, a)
        if (a.pushSubscriptionId.isNotEmpty()) {
            runCatching { pushSet(jmap, JSONObject().put("destroy", JSONArray().put(a.pushSubscriptionId))) }
        }
        val url = api.pushRegister(a.key)
        val keys = WebPush.newKeys(context, a.key)
        val r = pushSet(
            jmap,
            JSONObject().put(
                "create",
                JSONObject().put(
                    "s", JSONObject()
                        .put("deviceClientId", AppPrefs(context).deviceId + "-" + a.key)
                        .put("url", url)
                        .put("types", JSONArray(listOf("Email", "Mailbox")))
                        .put("keys", JSONObject().put("p256dh", keys.p256dh).put("auth", keys.auth))
                ),
            ),
        )
        val created = r.optJSONObject("created")?.optJSONObject("s")
        if (created == null) {
            android.util.Log.w("MailPush", "PushSubscription/set refused: $r")
            return
        }
        val id = created.optString("id")
        val expires = MailSync.parseDate(created.optString("expires")).takeIf { it > 0 } ?: (System.currentTimeMillis() + 7 * 86_400_000L)
        AccountStore.get(context).update(a.key) { it.copy(pushSubscriptionId = id, pushExpires = expires, pushVerified = false, pushCreated = System.currentTimeMillis()) }
    }

    /** The verification code Fastmail sent through the relay, handed back to Fastmail. */
    suspend fun verify(context: Context, accountKey: String, subscriptionId: String, code: String) {
        val a = AccountStore.get(context).get(accountKey) ?: return
        val r = pushSet(Jmap(context, a), JSONObject().put("update", JSONObject().put(subscriptionId, JSONObject().put("verificationCode", code))))
        if (r.optJSONObject("updated")?.has(subscriptionId) == true) {
            AccountStore.get(context).update(accountKey) { it.copy(pushVerified = true) }
        }
    }

    /** An encrypted message from the relay: decrypted with the account's own keys, then verification or a wake-up. */
    suspend fun onPush(context: Context, accountKey: String, body: ByteArray) {
        val text = WebPush.decrypt(context, accountKey, body) ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("@type")) {
            "PushVerification" -> verify(context, accountKey, json.optString("pushSubscriptionId"), json.optString("verificationCode"))
            "StateChange" -> com.opensolr.mail.sync.Work.syncNow(context)
        }
    }

    suspend fun remove(context: Context, a: MailAccount) {
        if (a.pushSubscriptionId.isNotEmpty()) runCatching { pushSet(Jmap(context, a), JSONObject().put("destroy", JSONArray().put(a.pushSubscriptionId))) }
        runCatching { OpensolrApi(AppPrefs(context)).pushUnregister(a.key) }
    }

    /** PushSubscription/set is not scoped to a mail account, so it goes without accountId. */
    private suspend fun pushSet(jmap: Jmap, args: JSONObject): JSONObject {
        val b = Jmap.Batch(listOf(Jmap.CORE))
        val id = b.add("PushSubscription/set", args)
        return jmap.send(b).get(id)
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}
