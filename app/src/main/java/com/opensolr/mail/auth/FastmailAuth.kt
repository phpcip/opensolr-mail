package com.opensolr.mail.auth

import android.content.Context
import android.net.Uri
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.net.AccountSignInException
import com.opensolr.mail.net.Http
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Fastmail sign-in (OAuth 2.0 + PKCE, public client) and the access tokens every call carries. */
object FastmailAuth {

    const val CLIENT_ID = "4c4e41ae"
    const val REDIRECT_URI = "com.opensolr.mail:/oauth/fastmail"
    private const val AUTHORIZE = "https://api.fastmail.com/oauth/authorize"
    private const val TOKEN = "https://api.fastmail.com/oauth/refresh"
    private const val REVOKE = "https://api.fastmail.com/oauth/revoke"
    const val SESSION = "https://api.fastmail.com/jmap/session"

    /** RFC 8707 resource indicator: Fastmail refuses an authorization that does not name the API it is for. */
    private const val RESOURCE = SESSION
    private const val SCOPE = "urn:ietf:params:oauth:scope:mail urn:ietf:params:oauth:scope:calendars urn:ietf:params:oauth:scope:contacts offline_access"

    private val locks = ConcurrentHashMap<String, Mutex>()

    fun start(context: Context, prefs: AppPrefs) {
        val verifier = Pkce.randomToken(48)
        val state = Pkce.randomToken(24)
        prefs.savePending("fastmail", verifier, state)
        val uri = Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", Pkce.challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("resource", RESOURCE)
            .build()
        Pkce.open(context, uri)
    }

    fun isCallback(uri: Uri): Boolean = uri.scheme == "com.opensolr.mail" && uri.path == "/oauth/fastmail"

    /** Finishes a sign-in: code → tokens → JMAP session → a stored account. Returns the account, new or refreshed. */
    suspend fun finish(context: Context, uri: Uri): MailAccount = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val state = uri.getQueryParameter("state").orEmpty()
        val iss = uri.getQueryParameter("iss")
        if (iss != null && iss != "https://api.fastmail.com") throw ServiceException("Unexpected issuer")
        val verifier = prefs.takePending("fastmail", state) ?: throw ServiceException("This sign-in expired. Try again.")
        uri.getQueryParameter("error")?.let { throw ServiceException(it) }
        val code = uri.getQueryParameter("code") ?: throw ServiceException("No code")

        val tok = tokenCall(
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", REDIRECT_URI)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", verifier)
                .add("resource", RESOURCE)
                .build()
        ) ?: throw ServiceException("Fastmail refused the sign-in")

        val access = tok.getString("access_token")
        val refresh = tok.getString("refresh_token")
        val expiresAt = System.currentTimeMillis() + tok.optLong("expires_in", 3600) * 1000

        val session = fetchSession(access)
        val caps = session.getJSONObject("primaryAccounts")
        val accountId = caps.optString("urn:ietf:params:jmap:mail")
        if (accountId.isEmpty()) throw ServiceException("This Fastmail login has no mail account")
        val username = session.optString("username")

        val store = AccountStore.get(context)
        // The same Fastmail mailbox, signed in again by any of its addresses, is the account already here.
        val existing = store.all().firstOrNull { it.jmapAccountId == accountId }
        val account = MailAccount(
            key = existing?.key ?: AppPrefs.randomHex(8),
            username = existing?.username ?: username,
            name = session.getJSONObject("accounts").optJSONObject(accountId)?.optString("name").orEmpty().ifEmpty { username },
            jmapAccountId = accountId,
            apiUrl = session.getString("apiUrl"),
            downloadUrl = session.getString("downloadUrl"),
            uploadUrl = session.getString("uploadUrl"),
            color = existing?.color ?: AccountStore.COLORS[store.all().size % AccountStore.COLORS.size],
            pushSubscriptionId = existing?.pushSubscriptionId.orEmpty(),
            pushExpires = existing?.pushExpires ?: 0L,
            calendarSync = existing?.calendarSync ?: true,
        )
        store.saveTokens(account.key, refresh, access, expiresAt)
        store.put(account)
        account
    }

    fun fetchSession(access: String): JSONObject {
        val req = Request.Builder().url(SESSION).header("Authorization", "Bearer $access").build()
        Http.mail.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ServiceException("Fastmail session: HTTP ${r.code}")
            return JSONObject(body)
        }
    }

    /** A valid access token for [key], refreshed (and the refresh token rotated) when it is within a minute of expiry or [force]d. */
    suspend fun accessToken(context: Context, key: String, force: Boolean = false): String {
        val store = AccountStore.get(context)
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val cached = store.accessToken(key)
            if (!force && cached != null && cached.second - 60_000 > System.currentTimeMillis()) return@withLock cached.first
            val refresh = store.refreshToken(key) ?: throw AccountSignInException(key)
            val tok = withContext(Dispatchers.IO) {
                tokenCall(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refresh)
                        .add("resource", RESOURCE)
                        .build()
                )
            } ?: throw AccountSignInException(key)
            val access = tok.getString("access_token")
            store.saveTokens(
                key,
                tok.optString("refresh_token").ifEmpty { refresh },
                access,
                System.currentTimeMillis() + tok.optLong("expires_in", 3600) * 1000,
            )
            access
        }
    }

    /** Revokes the login at Fastmail; best effort. */
    suspend fun revoke(context: Context, key: String) = withContext(Dispatchers.IO) {
        val store = AccountStore.get(context)
        val refresh = store.refreshToken(key) ?: return@withContext
        runCatching {
            val req = Request.Builder().url(REVOKE)
                .post(FormBody.Builder().add("client_id", CLIENT_ID).add("token", refresh).build()).build()
            Http.client.newCall(req).execute().close()
        }
    }

    /** POSTs to the token endpoint; null on invalid_grant (the login is gone), throws on transport errors. */
    private fun tokenCall(form: FormBody): JSONObject? {
        val req = Request.Builder().url(TOKEN).post(form).build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (r.isSuccessful && json.has("access_token")) return json
            if (json.optString("error") == "invalid_grant" && !json.optBoolean("temporary")) return null
            if (r.code in 400..499 && json.optString("error").isNotEmpty() && json.optString("error") != "invalid_grant") return null
            throw IOException("Fastmail token endpoint: HTTP ${r.code}")
        }
    }
}
