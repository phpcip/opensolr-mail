package com.opensolr.mail.auth

import android.content.Context
import android.net.Uri
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.net.Http
import com.opensolr.mail.net.RateLimitedException
import com.opensolr.mail.net.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Opensolr account sign-in for this app (/app/mail/sso), OAuth 2.0 + PKCE in the phone's browser. */
object OpensolrAuth {

    const val SITE = "https://opensolr.com"
    const val CLIENT_ID = "opensolr-mail"
    const val REDIRECT_URI = "$SITE/app/mail/callback"

    fun start(context: Context, prefs: AppPrefs) {
        val verifier = Pkce.randomToken(48)
        val state = Pkce.randomToken(24)
        prefs.savePending("opensolr", verifier, state)
        val uri = Uri.parse("$SITE/app/mail/sso").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", Pkce.challengeOf(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("device", Pkce.deviceLabel())
            .build()
        Pkce.open(context, uri)
    }

    fun isCallback(uri: Uri): Boolean =
        (uri.scheme == "opensolr-mail" && uri.host == "auth") ||
            (uri.scheme == "https" && uri.host == "opensolr.com" && uri.path == "/app/mail/callback")

    /** Swaps the code for the account email and API key and stores them. */
    suspend fun finish(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val state = uri.getQueryParameter("state").orEmpty()
        val verifier = prefs.takePending("opensolr", state) ?: throw ServiceException("This sign-in expired. Try again.")
        if (uri.getQueryParameter("error") != null) throw ServiceException("Access denied")
        val code = uri.getQueryParameter("code") ?: throw ServiceException("No code")

        val body = JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("code_verifier", verifier)
            .put("client_id", CLIENT_ID)
            .put("redirect_uri", REDIRECT_URI)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url("$SITE/app/mail/token").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            if (r.code == 429) throw RateLimitedException(60)
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            val email = json.optString("email")
            val key = json.optString("api_key")
            if (!json.optBoolean("status") || email.isBlank() || key.isBlank()) throw ServiceException("Opensolr refused the sign-in")
            prefs.saveSession(email, key)
        }
    }
}
