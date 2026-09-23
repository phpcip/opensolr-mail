package com.opensolr.mail.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One Fastmail login on this phone. [key] is the app's own id for it: local table rows, the push relay and the index all use it. */
data class MailAccount(
    val key: String,
    val username: String,
    val name: String,
    val jmapAccountId: String,
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val color: Int,
    val pushSubscriptionId: String = "",
    val pushExpires: Long = 0L,
    val calendarSync: Boolean = true,
    val pushVerified: Boolean = false,
    val pushCreated: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("username", username).put("name", name)
        .put("jmap_account_id", jmapAccountId).put("api_url", apiUrl)
        .put("download_url", downloadUrl).put("upload_url", uploadUrl)
        .put("color", color).put("push_sub", pushSubscriptionId).put("push_expires", pushExpires)
        .put("calendar_sync", calendarSync).put("push_verified", pushVerified).put("push_created", pushCreated)

    companion object {
        fun fromJson(o: JSONObject) = MailAccount(
            key = o.optString("key"),
            username = o.optString("username"),
            name = o.optString("name"),
            jmapAccountId = o.optString("jmap_account_id"),
            apiUrl = o.optString("api_url"),
            downloadUrl = o.optString("download_url"),
            uploadUrl = o.optString("upload_url"),
            color = o.optInt("color"),
            pushSubscriptionId = o.optString("push_sub"),
            pushExpires = o.optLong("push_expires"),
            calendarSync = o.optBoolean("calendar_sync", true),
            pushVerified = o.optBoolean("push_verified"),
            pushCreated = o.optLong("push_created"),
        )
    }
}

/** The Fastmail accounts, their tokens sealed with the Keystore. One instance per process. */
class AccountStore private constructor(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("mail_accounts", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<MailAccount>> = _accounts

    fun all(): List<MailAccount> = _accounts.value

    fun get(key: String): MailAccount? = _accounts.value.firstOrNull { it.key == key }

    private fun load(): List<MailAccount> {
        val raw = sp.getString(K_LIST, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(MailAccount::fromJson) }
                .filter { it.key.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun put(account: MailAccount) {
        // A changed account keeps its place; only a new one goes at the end.
        val current = _accounts.value
        val list = if (current.any { it.key == account.key }) current.map { if (it.key == account.key) account else it } else current + account
        sp.edit().putString(K_LIST, JSONArray(list.map { it.toJson() }).toString()).commit()
        _accounts.value = list
    }

    @Synchronized
    fun update(key: String, change: (MailAccount) -> MailAccount) {
        get(key)?.let { put(change(it)) }
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun remove(key: String) {
        val list = _accounts.value.filterNot { it.key == key }
        sp.edit().putString(K_LIST, JSONArray(list.map { it.toJson() }).toString())
            .remove("rt_$key").remove("at_$key").remove("ax_$key").commit()
        _accounts.value = list
    }

    fun refreshToken(key: String): String? = sp.getString("rt_$key", null)?.let { SecureStore.decrypt(it) }

    fun accessToken(key: String): Pair<String, Long>? {
        val t = sp.getString("at_$key", null)?.let { SecureStore.decrypt(it) } ?: return null
        return t to sp.getLong("ax_$key", 0L)
    }

    /** Stores a token pair right away: a rotated refresh token that is lost cannot be used again. */
    @SuppressLint("ApplySharedPref")
    fun saveTokens(key: String, refresh: String, access: String, expiresAt: Long) {
        sp.edit().putString("rt_$key", SecureStore.encrypt(refresh))
            .putString("at_$key", SecureStore.encrypt(access))
            .putLong("ax_$key", expiresAt).commit()
    }

    companion object {
        private const val K_LIST = "accounts"

        @Volatile
        private var instance: AccountStore? = null

        fun get(context: Context): AccountStore =
            instance ?: synchronized(this) { instance ?: AccountStore(context).also { instance = it } }

        /** Distinct colours for the account stripe, in the order accounts are added. */
        val COLORS = intArrayOf(
            0xFFC05520.toInt(), 0xFF2F6F8F.toInt(), 0xFF5B7F3A.toInt(), 0xFF8A4F9E.toInt(),
            0xFF9C6B1E.toInt(), 0xFF3F5AA8.toInt(), 0xFFA23B5A.toInt(), 0xFF2E7D6B.toInt(),
        )
    }
}
