package com.opensolr.mail.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.opensolr.mail.R
import com.opensolr.mail.data.Address
import com.opensolr.mail.jmap.MailActions
import com.opensolr.mail.ui.AccentButton
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.ComposeInit
import com.opensolr.mail.ui.Field
import com.opensolr.mail.ui.GhostButton
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtSize
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ComposeScreen(vm: AppViewModel, init: ComposeInit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identities = remember { vm.db.identities() }
    var identity by remember {
        mutableStateOf(
            identities.firstOrNull { it.acc == init.acc && it.id == init.identityId }
                ?: identities.firstOrNull { it.acc == init.acc }
                ?: identities.firstOrNull()
        )
    }
    var to by remember { mutableStateOf(init.to) }
    var cc by remember { mutableStateOf(init.cc) }
    var bcc by remember { mutableStateOf("") }
    var showCc by remember { mutableStateOf(init.cc.isNotBlank()) }
    var subject by remember { mutableStateOf(init.subject) }
    val signature = identity?.signature?.takeIf { it.isNotBlank() }?.let { "\n\n-- \n$it" }.orEmpty()
    var body by remember { mutableStateOf(if (init.draftId != null) init.body else signature + init.body) }
    val files = remember { mutableStateListOf<MailActions.OutFile>().apply { addAll(init.files) } }
    var picking by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val dirty = to != init.to || cc != init.cc || bcc.isNotBlank() || subject != init.subject || body.trim() != (signature + init.body).trim() || files.isNotEmpty()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            uris.forEach { uri -> copyIn(context, uri)?.let { files += it } ?: vm.toast(R.string.attach_failed) }
        }
    }

    fun outgoing(): MailActions.Outgoing? {
        val id = identity ?: return null
        return MailActions.Outgoing(
            acc = id.acc, identityId = id.id, from = Address(id.name, id.email),
            to = Address.parseInput(to), cc = Address.parseInput(cc), bcc = Address.parseInput(bcc),
            subject = subject, text = body, inReplyTo = init.inReplyTo, references = init.references,
            answeredId = init.answeredId, replacesDraftId = init.draftId.takeIf { id.acc == init.acc },
            attachments = files.toList(),
        )
    }

    BackHandler { if (dirty) leaving = true else vm.back() }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.new_message), onBack = { if (dirty) leaving = true else vm.back() }) {
            IconBtn(R.drawable.ic_drafts, {
                val o = outgoing()
                if (o == null) vm.toast(R.string.no_identity) else { vm.saveDraft(o); vm.back() }
            })
            IconBtn(R.drawable.ic_attach, { pick.launch("*/*") })
            IconBtn(R.drawable.ic_send, {
                val o = outgoing()
                when {
                    o == null -> vm.toast(R.string.no_identity)
                    o.to.isEmpty() && o.cc.isEmpty() && o.bcc.isEmpty() -> vm.toast(R.string.no_recipient)
                    else -> { vm.send(o); vm.back() }
                }
            }, tint = p.accent, strong = true)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().hapticClickable { picking = true }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.from), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.width(64.dp))
                Text(identity?.let { if (it.name.isBlank()) it.email else "${it.name} <${it.email}>" }.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Hairline()
            Line(stringResource(R.string.to)) { Field(to, { to = it }, "", Modifier.weight(1f), email = true) }
            Hairline()
            if (showCc) {
                Line(stringResource(R.string.cc)) { Field(cc, { cc = it }, "", Modifier.weight(1f), email = true) }
                Hairline()
                Line(stringResource(R.string.bcc)) { Field(bcc, { bcc = it }, "", Modifier.weight(1f), email = true) }
                Hairline()
            } else {
                Text(
                    stringResource(R.string.cc_bcc), style = MaterialTheme.typography.labelSmall, color = p.accent,
                    modifier = Modifier.hapticClickable { showCc = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Hairline()
            }
            Line(stringResource(R.string.subject)) { Field(subject, { subject = it }, "", Modifier.weight(1f)) }
            Hairline()
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_attach), null, tint = p.ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(f.name, style = MaterialTheme.typography.bodySmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(fmtSize(File(f.path).length()), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    IconBtn(R.drawable.ic_close, { files.remove(f); File(f.path).delete() })
                }
            }
            Field(body, { body = it }, stringResource(R.string.message_hint), Modifier.fillMaxWidth(), singleLine = false, minHeight = 320)
            Spacer(Modifier.height(bottomInset()))
        }
    }

    if (picking) {
        Dialog(onDismissRequest = { picking = false }) {
            Column(Modifier.fillMaxWidth().background(p.paper).border(1.dp, p.hairline).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                identities.forEach { id ->
                    Column(Modifier.fillMaxWidth().hapticClickable { identity = id; picking = false }.padding(16.dp)) {
                        Text(id.name.ifBlank { id.email }, style = MaterialTheme.typography.titleSmall, color = p.ink)
                        Text(id.email, style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    Hairline()
                }
            }
        }
    }

    if (leaving) {
        Dialog(onDismissRequest = { leaving = false }) {
            Column(Modifier.fillMaxWidth().background(p.paper).border(1.dp, p.hairline).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.leave_title), style = MaterialTheme.typography.titleMedium, color = p.ink)
                com.opensolr.mail.ui.ToolRow(listOf(
                    com.opensolr.mail.ui.Tool(R.drawable.ic_drafts, stringResource(R.string.tool_save), accent = true) {
                        outgoing()?.let { vm.saveDraft(it) }
                        leaving = false
                        vm.back()
                    },
                    com.opensolr.mail.ui.Tool(R.drawable.ic_trash, stringResource(R.string.discard)) {
                        files.forEach { File(it.path).delete() }
                        leaving = false
                        vm.back()
                    },
                    com.opensolr.mail.ui.Tool(R.drawable.ic_tool_keep, stringResource(R.string.tool_keep)) { leaving = false },
                ))
            }
        }
    }
}

@Composable
private fun Line(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.width(64.dp))
        content()
    }
}

/** Copies a picked file into the app, so the send can happen later, offline or after the picker's grant has expired. */
private suspend fun copyIn(context: android.content.Context, uri: Uri): MailActions.OutFile? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        var name = "file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val dir = File(context.filesDir, "outbox").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
        val target = File(dir, System.nanoTime().toString() + "_" + safe)
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (target.length() > 25L * 1024 * 1024) { target.delete(); return@runCatching null }
        MailActions.OutFile(target.path, name, type)
    }.getOrNull()
}
