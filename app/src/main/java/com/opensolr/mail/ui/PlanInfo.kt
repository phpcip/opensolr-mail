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
import com.opensolr.mail.ui.theme.LocalPalette
import java.util.Locale

object PlanInfo {
    const val PRICING_URL = "https://opensolr.com/pricing"
    const val DASHBOARD_URL = "https://opensolr.com/admin/solr_manager/dashboard"

    fun indexPanelUrl(indexName: String): String = "https://opensolr.com/admin/solr_manager/tools/" + Uri.encode(indexName)

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
        Notice(stringResource(R.string.search_needs_account_text), title = stringResource(R.string.search_needs_account_title))
        Spacer(Modifier.height(14.dp))
        ToolRow(listOf(Tool(R.drawable.ic_tool_signin, stringResource(R.string.tool_connect), accent = true) { vm.startOpensolrSignIn(context) }))
    }
}

/** Plan, usage bars, what each limit stops, and the way to a bigger plan. The same account zone as Opensolr Photos. */
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
        ToolRow(listOfNotNull(
            Tool(R.drawable.ic_tool_upgrade, stringResource(R.string.tool_upgrade), accent = true) { PlanInfo.open(context, PlanInfo.PRICING_URL) },
            Tool(R.drawable.ic_idx_reload, stringResource(if (vm.limitsLoading) R.string.acc_refreshing else R.string.acc_refresh), active = vm.limitsLoading) { vm.refreshLimits() },
            if (vm.prefs.indexName.isNotBlank()) Tool(R.drawable.ic_tool_open, stringResource(R.string.tool_index)) { PlanInfo.open(context, PlanInfo.indexPanelUrl(vm.prefs.indexName)) } else null,
            Tool(R.drawable.ic_tool_dashboard, stringResource(R.string.tool_dashboard)) { PlanInfo.open(context, PlanInfo.DASHBOARD_URL) },
            Tool(R.drawable.ic_tool_signout, stringResource(R.string.sign_out), onClick = onSignOut),
        ))
    }
}
