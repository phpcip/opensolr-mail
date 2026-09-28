package com.opensolr.mail.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opensolr.mail.AppText
import com.opensolr.mail.R
import com.opensolr.mail.data.AccountLimits
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.opensolr.mail.ui.theme.LocalPalette
import java.util.Locale

object PlanInfo {
    const val DASHBOARD_URL = "https://opensolr.com/admin/solr_manager/dashboard"
    const val PRODUCT_URL = "https://opensolr.com/opensolr-mail"
    const val PRIVACY_URL = "https://opensolr.com/opensolr-mail-docs/privacy"
    const val DELETE_ACCOUNT_URL = "https://opensolr.com/delete-account"

    data class Warning(val title: String, val text: String)

    private const val WARN_AT = 0.9

    /** What the plan stops, or is about to stop, for the mail index. */
    fun warnings(l: AccountLimits): List<Warning> {
        val out = ArrayList<Warning>()
        if (l.diskLimitMb > 0) {
            val share = l.diskUsedMb / l.diskLimitMb
            if (share >= 1.0) out += Warning(AppText.s(R.string.pw_disk_full_title), AppText.s(R.string.pw_disk_full_text))
            else if (share >= WARN_AT) out += Warning(AppText.s(R.string.pw_disk_90_title), AppText.s(R.string.pw_disk_90_text, percent(share)))
        }
        if (l.bandwidthLimitMb > 0) {
            val share = l.bandwidthUsedMb / l.bandwidthLimitMb
            if (share >= 1.0) out += Warning(AppText.s(R.string.pw_bw_full_title), AppText.s(R.string.pw_bw_full_text))
            else if (share >= WARN_AT) out += Warning(AppText.s(R.string.pw_bw_90_title), AppText.s(R.string.pw_bw_90_text, percent(share)))
        }
        if (!l.vectorAllowed) {
            out += Warning(AppText.s(R.string.pw_no_vec_title), AppText.s(R.string.pw_no_vec_text))
        } else if (l.maxAiRequests > 0) {
            val share = l.aiRequestsUsed.toDouble() / l.maxAiRequests
            if (share >= 1.0) out += Warning(AppText.s(R.string.pw_ai_full_title), AppText.s(R.string.pw_ai_full_text))
            else if (share >= WARN_AT) out += Warning(AppText.s(R.string.pw_ai_90_title), AppText.s(R.string.pw_ai_90_text, count(l.aiRequestsUsed.toLong()), count(l.maxAiRequests.toLong())))
        }
        return out
    }

    /** The Opensolr app, which manages the indexes of the account; its product page links every way to install it. */
    const val OPENSOLR_APP = "com.opensolr.main"
    const val OPENSOLR_APP_URL = "https://opensolr.com/opensolr-app"

    /** Opens the Opensolr app on [indexName]; false when it is not installed. */
    fun openInOpensolrApp(context: Context, indexName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(OPENSOLR_APP) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (indexName.isNotBlank()) launch.putExtra("open_index", indexName)
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun mb(mb: Double): String = if (mb >= 1000) String.format(Locale.US, "%.1f GB", mb / 1000) else String.format(Locale.US, "%.0f MB", mb)

    fun count(v: Long): String = String.format(Locale.US, "%,d", v)

    private fun percent(share: Double): String = String.format(Locale.US, "%d%%", (share * 100).toInt())
}

/** A used-of-limit line with its bar, the bar turning to the accent from 90%. */
@Composable
fun UsageRow(label: String, used: String, limit: String, fraction: Float?) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink)
            Text(stringResource(R.string.acc_x_of_y, used, limit), style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
        if (fraction != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (fraction >= 0.9f) p.accent else p.ink,
                trackColor = p.chip,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = p.hairline, thickness = 1.dp)
    }
}

/** Search is an Opensolr service: what is missing without the account, and the way to connect it. */
@Composable
fun NeedsOpensolr(vm: AppViewModel) {
    val context = LocalContext.current
    Column {
        // What Opensolr adds to this app, briefly, then the sign-in and the product page; no plans or prices.
        Notice(stringResource(R.string.opensolr_pitch_text), title = stringResource(R.string.opensolr_pitch_title))
        Spacer(Modifier.height(14.dp))
        ToolRow(listOf(
            Tool(R.drawable.ic_tool_signin, stringResource(R.string.tool_connect), accent = true) { vm.startOpensolrSignIn(context) },
            Tool(R.drawable.ic_tool_open, stringResource(R.string.tool_go_opensolr)) { PlanInfo.open(context, PlanInfo.PRODUCT_URL) },
        ))
    }
}

/** Plan, usage bars and what each limit stops. The same account zone as Opensolr Photos. */
@Composable
fun PlanDetails(vm: AppViewModel, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val l = vm.limits
    Column {
        InfoRow(stringResource(R.string.idx_row_account), vm.prefs.email)
        if (l != null) {
            InfoRow(stringResource(R.string.acc_plan), l.planLabel)
            PlanInfo.warnings(l).forEach { w ->
                Spacer(Modifier.height(10.dp))
                Notice(w.text, title = w.title)
            }
            Spacer(Modifier.height(6.dp))
            UsageRow(stringResource(R.string.acc_disk), PlanInfo.mb(l.diskUsedMb), PlanInfo.mb(l.diskLimitMb), if (l.diskLimitMb > 0) (l.diskUsedMb / l.diskLimitMb).toFloat() else null)
            UsageRow(stringResource(R.string.acc_bw), PlanInfo.mb(l.bandwidthUsedMb), PlanInfo.mb(l.bandwidthLimitMb), if (l.bandwidthLimitMb > 0) (l.bandwidthUsedMb / l.bandwidthLimitMb).toFloat() else null)
            if (l.maxAiRequests > 0) {
                UsageRow(stringResource(R.string.acc_ai_month), PlanInfo.count(l.aiRequestsUsed.toLong()), PlanInfo.count(l.maxAiRequests.toLong()), l.aiRequestsUsed.toFloat() / l.maxAiRequests)
            } else {
                InfoRow(stringResource(R.string.acc_ai_month), stringResource(if (l.vectorAllowed) R.string.acc_no_cap else R.string.acc_not_included))
            }
            InfoRow(stringResource(R.string.acc_meaning_search), stringResource(if (l.vectorAllowed) R.string.acc_included else R.string.acc_not_included))
            InfoRow(stringResource(R.string.acc_indexes), stringResource(R.string.acc_x_of_y, PlanInfo.count(l.indexesUsed.toLong()), PlanInfo.count(l.indexLimit.toLong())))
            if (l.indexedDocs > 0) InfoRow(stringResource(R.string.acc_docs), PlanInfo.count(l.indexedDocs))
            InfoRow(stringResource(R.string.acc_updated), fmtDate(l.refreshedAt))
            Spacer(Modifier.height(16.dp))
            Notice(stringResource(R.string.acc_limit_text), title = stringResource(R.string.acc_limit_title))
        }
        vm.limitsError?.let {
            Spacer(Modifier.height(12.dp))
            Notice(it, title = stringResource(R.string.acc_could_not_refresh))
        }
        Spacer(Modifier.height(12.dp))
        // The index is managed, and deleted if wanted, in the Opensolr app, or in the control panel without it.
        var getApp by remember { mutableStateOf(false) }
        GhostButton(stringResource(R.string.manage_index), onClick = {
            if (!PlanInfo.openInOpensolrApp(context, vm.prefs.indexName)) getApp = true
        }, modifier = Modifier.fillMaxWidth())
        if (getApp) {
            val p = LocalPalette.current
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { getApp = false },
                title = { Text(stringResource(R.string.get_app_title)) },
                text = { Text(stringResource(R.string.get_app_text)) },
                confirmButton = { DialogButton(stringResource(R.string.get_app_install), { getApp = false; PlanInfo.open(context, PlanInfo.OPENSOLR_APP_URL) }, accent = true) },
                dismissButton = { DialogButton(stringResource(R.string.get_app_browser), { getApp = false; PlanInfo.open(context, PlanInfo.DASHBOARD_URL) }) },
                containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.ink,
            )
        }
        Spacer(Modifier.height(12.dp))
        ToolRow(listOfNotNull(
            Tool(R.drawable.ic_idx_reload, stringResource(if (vm.limitsLoading) R.string.acc_refreshing else R.string.acc_refresh), active = vm.limitsLoading) { vm.refreshLimits() },
            Tool(R.drawable.ic_tool_dashboard, stringResource(R.string.tool_dashboard)) { PlanInfo.open(context, PlanInfo.DASHBOARD_URL) },
            Tool(R.drawable.ic_tool_signout, stringResource(R.string.sign_out), onClick = onSignOut),
            Tool(R.drawable.ic_trash, stringResource(R.string.tool_delete_account)) { PlanInfo.open(context, PlanInfo.DELETE_ACCOUNT_URL) },
        ))
    }
}
