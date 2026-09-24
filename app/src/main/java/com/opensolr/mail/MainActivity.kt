package com.opensolr.mail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.opensolr.mail.ui.AppLanguage
import com.opensolr.mail.ui.AppRoot
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.ComposeInit
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.theme.OpensolrTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppText.init(this)
        setContent { OpensolrTheme { AppRoot(vm) } }
        handle(intent)
        vm.checkForUpdate()
        askMissingPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
        askMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (vm.store.all().isNotEmpty()) vm.refresh()
        // Also learns at once when the phone was signed out from Account > Devices.
        vm.refreshLimits(30_000)
    }

    /** Notifications, and the calendar store once an account syncs its calendars. */
    private fun askMissingPermissions() {
        if (vm.store.all().isEmpty()) return
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (vm.store.all().any { it.calendarSync }) {
            wanted += Manifest.permission.READ_CALENDAR
            wanted += Manifest.permission.WRITE_CALENDAR
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    private fun handle(source: Intent?) {
        val intent = source ?: return
        (intent.getStringExtra(EXTRA_AUTH_CALLBACK))?.let {
            intent.removeExtra(EXTRA_AUTH_CALLBACK)
            vm.onAuthCallback(Uri.parse(it))
        }
        val acc = intent.getStringExtra(EXTRA_ACC)
        val thread = intent.getStringExtra(EXTRA_THREAD)
        if (acc != null && thread != null) {
            intent.removeExtra(EXTRA_ACC); intent.removeExtra(EXTRA_THREAD)
            vm.go(Screen.Thread(acc, thread))
        }
        if ((intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW) && intent.data?.scheme == "mailto") {
            val raw = intent.data.toString()
            intent.data = null
            vm.go(Screen.Compose(mailto(raw)))
        }
    }

    /** mailto:a@b?subject=..&body=..&cc=.. */
    private fun mailto(raw: String): ComposeInit {
        val body = raw.removePrefix("mailto:")
        val to = Uri.decode(body.substringBefore('?'))
        val q = body.substringAfter('?', "").split('&').mapNotNull {
            val k = it.substringBefore('=').lowercase()
            if (k.isEmpty()) null else k to Uri.decode(it.substringAfter('=', ""))
        }.toMap()
        return ComposeInit(to = listOfNotNull(to.ifBlank { null }, q["to"]).joinToString(", "), cc = q["cc"].orEmpty(), subject = q["subject"].orEmpty(), body = q["body"].orEmpty())
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    companion object {
        const val EXTRA_AUTH_CALLBACK = "auth_callback"
        const val EXTRA_ACC = "acc"
        const val EXTRA_THREAD = "thread"
    }
}
