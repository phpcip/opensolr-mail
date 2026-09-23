package com.opensolr.mail.dav

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.net.AccountSignInException
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Android calls this whenever a calendar of ours needs syncing, including right after an edit in Google Calendar. */
class CalendarSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
        val mail = AccountStore.get(context).all().firstOrNull { it.username == account.name && it.calendarSync } ?: return
        try {
            runBlocking { CalendarSync(context, mail).run() }
        } catch (e: AccountSignInException) {
            result.stats.numAuthExceptions++
        } catch (e: IOException) {
            android.util.Log.w("CalendarSync", "sync failed", e)
            result.stats.numIoExceptions++
        } catch (e: Exception) {
            android.util.Log.w("CalendarSync", "sync failed", e)
            result.stats.numParseExceptions++
        }
    }
}

class CalendarSyncService : Service() {
    private val adapter by lazy { CalendarSyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder
}

/** The account type Android needs to hold synced calendars. Accounts are only ever added from inside the app. */
class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(r: AccountAuthenticatorResponse?, t: String?): Bundle? = null
    override fun addAccount(r: AccountAuthenticatorResponse?, t: String?, a: String?, f: Array<out String>?, o: Bundle?): Bundle? = null
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, o: Bundle?): Bundle? = null
    override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(t: String?): String? = null
    override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, t: String?, o: Bundle?): Bundle? = null
    override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, f: Array<out String>?): Bundle = Bundle().apply { putBoolean("booleanResult", false) }
}

class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
