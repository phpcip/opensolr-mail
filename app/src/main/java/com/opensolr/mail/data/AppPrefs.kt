package com.opensolr.mail.data

import android.annotation.SuppressLint
import android.content.Context
import java.security.SecureRandom

/** The Opensolr side of the app: session, device id, the mail index, and small settings. */
class AppPrefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("opensolr_mail", Context.MODE_PRIVATE)

    val signedIn: Boolean get() = email.isNotEmpty() && apiKey.isNotEmpty()

    val email: String get() = sp.getString(K_EMAIL, "") ?: ""

    val apiKey: String
        get() = sp.getString(K_KEY, null)?.let { SecureStore.decrypt(it) } ?: ""

    @SuppressLint("ApplySharedPref")
    fun saveSession(email: String, apiKey: String) {
        sp.edit().putString(K_EMAIL, email).putString(K_KEY, SecureStore.encrypt(apiKey)).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun clearSession() {
        sp.edit().remove(K_EMAIL).remove(K_KEY).remove(K_INDEX).remove(K_CONN).remove(K_VECTOR).remove(K_LIMITS).commit()
    }

    /** Random id this install minted for itself; the server knows the phone by it. */
    val deviceId: String
        @SuppressLint("ApplySharedPref")
        get() {
            sp.getString(K_DEVICE, null)?.let { return it }
            val id = randomHex(16)
            sp.edit().putString(K_DEVICE, id).commit()
            return id
        }

    var indexName: String
        get() = sp.getString(K_INDEX, "") ?: ""
        set(v) = sp.edit().putString(K_INDEX, v).apply()

    /** The index connection (url, user, password) as sealed JSON. */
    var connectionJson: String
        get() = sp.getString(K_CONN, null)?.let { SecureStore.decrypt(it) } ?: ""
        set(v) = sp.edit().putString(K_CONN, SecureStore.encrypt(v)).apply()

    /** Last answer of the plan: may vectors be made. */
    var vectorAllowed: Boolean
        get() = sp.getBoolean(K_VECTOR, true)
        set(v) = sp.edit().putBoolean(K_VECTOR, v).apply()

    /** The plan and its usage as last read from Opensolr; null before the first read. */
    var limits: AccountLimits?
        get() = sp.getString(K_LIMITS, null)?.let { runCatching { AccountLimits.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().putString(K_LIMITS, v?.toJson()).apply()

    /** Epoch ms until which embedding waits (monthly AI quota spent). */
    var embedPausedUntil: Long
        get() = sp.getLong(K_EMBED_PAUSE, 0L)
        set(v) = sp.edit().putLong(K_EMBED_PAUSE, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(K_UPDATE, 0L)
        set(v) = sp.edit().putLong(K_UPDATE, v).apply()

    var notifyNewMail: Boolean
        get() = sp.getBoolean(K_NOTIFY, true)
        set(v) = sp.edit().putBoolean(K_NOTIFY, v).apply()

    /** Taps back on buttons, swipes and scrolling; off silences every haptic in the app. */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var remoteImages: Boolean
        get() = sp.getBoolean(K_IMAGES, false)
        set(v) = sp.edit().putBoolean(K_IMAGES, v).apply()

    /** Search: vectors on (AI), recency multiplier (Fresh), and the lexical share of the hybrid fusion. */
    var aiSearch: Boolean
        get() = sp.getBoolean("ai_search", true)
        set(v) = sp.edit().putBoolean("ai_search", v).apply()

    var freshSearch: Boolean
        get() = sp.getBoolean("fresh_search", false)
        set(v) = sp.edit().putBoolean("fresh_search", v).apply()

    var lexicalWeight: Float
        get() = sp.getFloat("lexical_weight", 0.3f).coerceIn(0f, 1f)
        set(v) = sp.edit().putFloat("lexical_weight", v.coerceIn(0f, 1f)).apply()

    var groupBy: String
        get() = sp.getString("group_by", "NONE") ?: "NONE"
        set(v) = sp.edit().putString("group_by", v).apply()

    /** Sections of the mailbox list the reader opened; every section starts folded. */
    var openSections: Set<String>
        get() = sp.getStringSet("open_sections", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("open_sections", v).apply()

    /** A named set of keys (folded groups, open zones), kept across launches. */
    fun keys(name: String): Set<String> = sp.getStringSet("set_$name", emptySet()) ?: emptySet()

    fun saveKeys(name: String, keys: Set<String>) {
        sp.edit().putStringSet("set_$name", HashSet(keys)).apply()
    }

    /** How the mail lists are grouped; by day unless changed. */
    var listGroup: String
        get() = sp.getString("list_group", "DAY") ?: "DAY"
        set(v) = sp.edit().putString("list_group", v).apply()

    /** Where a list was left: first visible row and its offset, per list. */
    fun scrollOf(key: String): Pair<Int, Int> = sp.getInt("scroll_i_$key", 0) to sp.getInt("scroll_o_$key", 0)

    fun saveScroll(key: String, index: Int, offset: Int) {
        sp.edit().putInt("scroll_i_$key", index).putInt("scroll_o_$key", offset).apply()
    }

    @SuppressLint("ApplySharedPref")
    fun savePending(kind: String, verifier: String, state: String) {
        sp.edit().putString("pending_${kind}_verifier", verifier).putString("pending_${kind}_state", state)
            .putLong("pending_${kind}_at", System.currentTimeMillis()).commit()
    }

    /** The verifier of a sign-in started here within the last 15 minutes whose state matches; consumed. */
    @SuppressLint("ApplySharedPref")
    fun takePending(kind: String, state: String): String? {
        val v = sp.getString("pending_${kind}_verifier", null)
        val s = sp.getString("pending_${kind}_state", null)
        val at = sp.getLong("pending_${kind}_at", 0L)
        sp.edit().remove("pending_${kind}_verifier").remove("pending_${kind}_state").remove("pending_${kind}_at").commit()
        if (v == null || s == null || s != state) return null
        if (System.currentTimeMillis() - at > 15 * 60_000L) return null
        return v
    }

    companion object {
        private const val K_EMAIL = "email"
        private const val K_KEY = "api_key"
        private const val K_DEVICE = "device_id"
        private const val K_INDEX = "index_name"
        private const val K_CONN = "index_conn"
        private const val K_VECTOR = "vector_allowed"
        private const val K_LIMITS = "account_limits"
        private const val K_EMBED_PAUSE = "embed_paused_until"
        private const val K_UPDATE = "last_update_check"
        private const val K_NOTIFY = "notify_new_mail"
        private const val K_IMAGES = "remote_images"

        private val random = SecureRandom()

        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes).also { random.nextBytes(it) }
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
