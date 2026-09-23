package com.opensolr.mail.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensolr.mail.R
import com.opensolr.mail.ui.AccentButton
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.GhostButton
import com.opensolr.mail.ui.theme.LocalPalette

/** The first mail account. Search is an Opensolr service and can be connected now or later in Settings. */
@Composable
fun SetupScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = p.ink)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.setup_lead), style = MaterialTheme.typography.bodyLarge, color = p.muted)
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.setup_fastmail), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.setup_step2_body), style = MaterialTheme.typography.bodyMedium, color = p.muted)
        Spacer(Modifier.height(20.dp))
        com.opensolr.mail.ui.ToolRow(listOf(com.opensolr.mail.ui.Tool(R.drawable.ic_tool_signin, stringResource(R.string.tool_fastmail), accent = true) { vm.startFastmailSignIn(context) }))
        Spacer(Modifier.height(36.dp))
        Text(stringResource(R.string.setup_search), style = MaterialTheme.typography.titleMedium, color = p.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.search_needs_account_text), style = MaterialTheme.typography.bodyMedium, color = p.muted)
        Spacer(Modifier.height(20.dp))
        if (vm.signedIn) {
            Text(stringResource(R.string.setup_opensolr_connected, vm.prefs.email), style = MaterialTheme.typography.bodyMedium, color = p.accent)
        } else {
            com.opensolr.mail.ui.ToolRow(listOf(com.opensolr.mail.ui.Tool(R.drawable.ic_tool_signin, stringResource(R.string.tool_connect)) { vm.startOpensolrSignIn(context) }))
        }
    }
}
