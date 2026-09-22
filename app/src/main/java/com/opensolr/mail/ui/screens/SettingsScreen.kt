package com.opensolr.mail.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.mail.BuildConfig
import com.opensolr.mail.R
import com.opensolr.mail.ui.AccentButton
import com.opensolr.mail.ui.AppLanguage
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.GhostButton
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.Haptics
import com.opensolr.mail.ui.InfoRow
import com.opensolr.mail.ui.Notice
import com.opensolr.mail.ui.ScreenHeader
import com.opensolr.mail.ui.Zone
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtDate
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.theme.LocalPalette
import java.util.Locale

/** Settings in zones, all folded until tapped, as in Opensolr Photos. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val accounts by vm.store.accounts.collectAsState()
    val s by vm.indexStatus.collectAsState()
    val view = LocalView.current
    var notify by remember { mutableStateOf(vm.prefs.notifyNewMail) }
    var images by remember { mutableStateOf(vm.prefs.remoteImages) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val open = vm.zonesOpen
    fun n(v: Long) = String.format(Locale.US, "%,d", v)
    LaunchedEffect(Unit) { vm.refreshLimits(minAgeMs = 60_000) }
    // Each index run reads the plan again; the screen picks that up.
    LaunchedEffect(s.at) { vm.prefs.limits?.let { if (it.refreshedAt > (vm.limits?.refreshedAt ?: 0)) vm.limits = it } }

    val settingsScroll = com.opensolr.mail.ui.rememberScrollMemory(vm.prefs, "settings")
    val settingsMarks = remember { com.opensolr.mail.ui.ScrollMarks() }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(com.opensolr.mail.ui.LocalScrollMarks provides settingsMarks) {
        Column(Modifier.fillMaxSize().verticalScroll(settingsScroll).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenHeader(stringResource(R.string.settings), onBack = { vm.back() }) }
            val allZones = setOf("updates", "accounts", "index", "search", "opensolr", "reading", "language")
            val anyOpen = allZones.any { it in open }
            com.opensolr.mail.ui.IconBtn(if (anyOpen) R.drawable.ic_collapse_all else R.drawable.ic_expand_all, { vm.setKeySet("settings_zones", if (anyOpen) emptySet() else allZones) })
        }

        Zone(stringResource(R.string.zone_updates), if (vm.update != null) 1 else 0, "updates" in open, { vm.toggleZone("updates") }) {
            Column {
                InfoRow(stringResource(R.string.app), BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(10.dp))
                GhostButton(stringResource(if (vm.updateChecking) R.string.acc_checking else R.string.check_updates), { vm.checkForUpdateNow() }, Modifier.fillMaxWidth())
                vm.updateResult?.let {
                    Spacer(Modifier.height(12.dp))
                    Notice(it, title = stringResource(if (vm.update != null) R.string.acc_update_available else R.string.acc_version))
                }
                vm.update?.let { newer ->
                    Spacer(Modifier.height(12.dp))
                    if (vm.updateResult == null) {
                        Notice(newer.notes.ifBlank { stringResource(R.string.acc_update_notes) }, title = stringResource(R.string.acc_version_available, newer.version))
                        Spacer(Modifier.height(10.dp))
                    }
                    // A copy from Google Play is updated by Play; every other copy updates itself from the GitHub release.
                    if (com.opensolr.mail.net.SelfUpdate.fromPlay(context)) {
                        AccentButton(stringResource(R.string.acc_update_now, newer.version), { com.opensolr.mail.net.SelfUpdate.openPlay(context) }, Modifier.fillMaxWidth())
                    } else {
                        val progress = vm.updateProgress
                        AccentButton(
                            if (progress != null) stringResource(R.string.acc_downloading, progress) else stringResource(R.string.acc_update_now, newer.version),
                            { vm.installUpdate(context) }, Modifier.fillMaxWidth(), enabled = progress == null,
                        )
                        vm.updateInstallError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                    }
                }
            }
        }

        Zone(stringResource(R.string.mail_accounts), vm.needsLogin.size, "accounts" in open, { vm.toggleZone("accounts") }) {
            Column {
                accounts.forEach { a ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Color(a.color)))
                        Spacer(Modifier.width(10.dp))
                        Text(a.username, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (a.key in vm.needsLogin) {
                        Spacer(Modifier.height(8.dp))
                        Notice(stringResource(R.string.sign_in_again_needed))
                    }
                    Toggle(stringResource(R.string.calendar_sync), a.calendarSync) { vm.setCalendarSync(a, it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(stringResource(R.string.sign_in_again), { vm.startFastmailSignIn(context) }, Modifier.weight(1f))
                        if (confirmRemove == a.key) AccentButton(stringResource(R.string.confirm_remove), { confirmRemove = null; vm.removeAccount(a) }, Modifier.weight(1f))
                        else GhostButton(stringResource(R.string.remove), { confirmRemove = a.key }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Hairline()
                }
                Spacer(Modifier.height(12.dp))
                GhostButton(stringResource(R.string.add_account), { vm.startFastmailSignIn(context) }, Modifier.fillMaxWidth(), icon = R.drawable.ic_add)
            }
        }

        val warnings = remember(vm.limits) { vm.limits?.let { com.opensolr.mail.ui.PlanInfo.warnings(it) } ?: emptyList() }
        Zone(stringResource(R.string.idx_title), if (!vm.signedIn || s.error != null || s.noRoom || vm.limits?.closed == true) 1 else 0, "index" in open, { vm.toggleZone("index") }) {
            Column {
                if (!vm.signedIn) {
                    com.opensolr.mail.ui.NeedsOpensolr(vm)
                    return@Column
                }
                if (s.noRoom) Notice(stringResource(R.string.index_no_room))
                vm.limits?.takeIf { it.closed }?.let { Notice(stringResource(R.string.search_closed_text), title = stringResource(R.string.search_closed_title)) }
                s.error?.let { Notice(it, title = stringResource(R.string.idx_error_title)) }
                if (s.noRoom || s.error != null || vm.limits?.closed == true) Spacer(Modifier.height(8.dp))
                val state = when {
                    s.running && s.phase == com.opensolr.mail.index.MailIndexer.Phase.ATTACHMENTS -> R.string.idx_phase_attachments
                    s.running && s.phase == com.opensolr.mail.index.MailIndexer.Phase.HISTORY -> R.string.idx_phase_history
                    s.running && s.phase == com.opensolr.mail.index.MailIndexer.Phase.MAIL -> R.string.idx_phase_mail
                    s.running -> R.string.idx_running
                    s.pending > 0 || !s.historyDone -> R.string.idx_row_waiting
                    s.attachmentsLeft > 0 -> R.string.idx_waiting_wifi
                    else -> R.string.idx_row_done
                }
                InfoRow(stringResource(R.string.idx_row_state), stringResource(state))
                InfoRow(stringResource(R.string.idx_row_indexed), if (s.indexed < 0) "\u2014" else n(s.indexed))
                InfoRow(stringResource(R.string.idx_row_meaning), if (s.withMeaning < 0) "\u2014" else n(s.withMeaning))
                InfoRow(stringResource(R.string.idx_row_pending), n(s.pending.toLong()))
                InfoRow(stringResource(R.string.idx_row_attachments), if (s.attachmentsLeft < 0) "\u2014" else n(s.attachmentsLeft))
                InfoRow(stringResource(R.string.idx_row_history), stringResource(if (s.historyDone) R.string.idx_row_done else R.string.idx_row_reading))
                InfoRow(stringResource(R.string.idx_row_last), if (s.at > 0) fmtDate(s.at) else "\u2014")
                InfoRow(stringResource(R.string.idx_row_name), vm.prefs.indexName.ifBlank { "\u2014" })
                if (s.running) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.hairline)
                }
                if (s.attachmentsLeft > 0) { Spacer(Modifier.height(8.dp)); Notice(stringResource(R.string.idx_att_wifi)) }
                if (!vm.prefs.vectorAllowed) { Spacer(Modifier.height(8.dp)); Notice(stringResource(R.string.index_words_only)) }
                if (vm.prefs.embedPausedUntil > System.currentTimeMillis()) { Spacer(Modifier.height(8.dp)); Notice(stringResource(R.string.index_quota)) }
                Spacer(Modifier.height(12.dp))
                GhostButton(stringResource(R.string.idx_run_now), { vm.indexNow() }, Modifier.fillMaxWidth())
            }
        }

        Zone(stringResource(R.string.zone_search), 0, "search" in open, { vm.toggleZone("search") }) {
            Column {
                var lw by remember { mutableStateOf(vm.prefs.lexicalWeight) }
                Text(stringResource(R.string.alpha_title), style = MaterialTheme.typography.titleSmall, color = p.ink)
                Text(stringResource(R.string.alpha_value, ((1f - lw) * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = p.muted)
                androidx.compose.material3.Slider(
                    value = 1f - lw,
                    onValueChange = { v -> val next = (1f - v).coerceIn(0f, 1f); if ((next * 20).toInt() != (lw * 20).toInt()) Haptics.tick(view, false); lw = next },
                    onValueChangeFinished = { vm.prefs.lexicalWeight = lw },
                    steps = 19,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.accentFill, activeTrackColor = p.accentFill, inactiveTrackColor = p.hairline),
                )
                Row {
                    Text(stringResource(R.string.alpha_words), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.alpha_meaning), style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
        }

        Zone(stringResource(R.string.opensolr_account), if (!vm.signedIn) 1 else warnings.size, "opensolr" in open, { vm.toggleZone("opensolr") }) {
            Column {
                if (!vm.signedIn) {
                    com.opensolr.mail.ui.NeedsOpensolr(vm)
                    return@Column
                }
                com.opensolr.mail.ui.PlanDetails(vm)
                Spacer(Modifier.height(10.dp))
                if (confirmSignOut) AccentButton(stringResource(R.string.confirm_sign_out), { confirmSignOut = false; vm.signOutOpensolr() }, Modifier.fillMaxWidth())
                else GhostButton(stringResource(R.string.sign_out), { confirmSignOut = true }, Modifier.fillMaxWidth())
            }
        }

        Zone(stringResource(R.string.reading), 0, "reading" in open, { vm.toggleZone("reading") }) {
            Column {
                Toggle(stringResource(R.string.notify_new_mail), notify) { notify = it; vm.prefs.notifyNewMail = it }
                Hairline()
                Toggle(stringResource(R.string.remote_images), images) { images = it; vm.prefs.remoteImages = it }
            }
        }

        Zone(stringResource(R.string.language), 0, "language" in open, { vm.toggleZone("language") }) {
            Column {
                val current = AppLanguage.chosen(context)
                (listOf("" to stringResource(R.string.language_phone)) + AppLanguage.SUPPORTED).forEach { (tag, name) ->
                    Row(Modifier.fillMaxWidth().hapticClickable { activityOf(context)?.let { AppLanguage.choose(it, tag) } }.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.weight(1f))
                        if (tag == current) Text("✓", style = MaterialTheme.typography.titleSmall, color = p.accent)
                    }
                    Hairline()
                }
            }
        }
        Spacer(Modifier.height(bottomInset() + 40.dp))
    }
        }
        FastScroller(settingsScroll, settingsMarks)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().hapticClickable { Haptics.toggle(view, !on); onChange(!on) }.height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.weight(1f))
        Box(Modifier.size(width = 40.dp, height = 22.dp).background(if (on) p.accentFill else p.hairline), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(3.dp).size(16.dp).background(p.paper))
        }
    }
}

private fun activityOf(context: Context): Activity? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
