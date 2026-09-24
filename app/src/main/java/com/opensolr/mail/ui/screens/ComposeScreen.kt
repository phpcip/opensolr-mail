package com.opensolr.mail.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.opensolr.mail.R
import androidx.compose.runtime.LaunchedEffect
import com.opensolr.mail.ui.BodyField
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
    // The cursor starts at the very top, above the signature and the quoted message, ready to type.
    var bodyValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(if (init.draftId != null) init.body else signature + init.body, androidx.compose.ui.text.TextRange(0))) }
    val body = bodyValue.text
    val toFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val bodyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // A reply or forward with its recipients filled opens ready to write; a new message opens on To.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        runCatching { if (init.to.isNotBlank() || init.draftId != null) bodyFocus.requestFocus() else toFocus.requestFocus() }
        keyboard?.show()
    }
    val files = remember { mutableStateListOf<MailActions.OutFile>().apply { addAll(init.files) } }

    // Recipient suggestions under the field being typed in: the mail index and the phone's contacts, one list.
    val suggester = remember { com.opensolr.mail.search.RecipientSuggest(context) }
    var active by remember { mutableStateOf<String?>(null) }
    var contactsOk by remember { mutableStateOf(suggester.contactsAllowed()) }
    var suggestions by remember { mutableStateOf<List<com.opensolr.mail.search.RecipientSuggest.Suggestion>>(emptyList()) }
    val activeText = when (active) { "to" -> to; "cc" -> cc; "bcc" -> bcc; else -> "" }
    val typing = Address.splitInput(activeText).last().trim().takeUnless { it.contains('<') }.orEmpty()
    LaunchedEffect(active, typing, contactsOk) {
        contactsOk = suggester.contactsAllowed()
        if (typing.isEmpty()) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(180)
        suggestions = runCatching { suggester.suggest(typing) }.getOrDefault(emptyList())
    }
    fun pickRecipient(s: com.opensolr.mail.search.RecipientSuggest.Suggestion) {
        val parts = Address.splitInput(activeText).dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
        val value = (parts + Address(s.name, s.email).formatted()).joinToString(", ") + ", "
        when (active) { "to" -> to = value; "cc" -> cc = value; "bcc" -> bcc = value }
        suggestions = emptyList()
    }
    val activity = context as? android.app.Activity
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> contactsOk = granted }
    fun allowContacts() {
        // Asked once and refused for good: Android shows no dialog any more, so the app's settings open instead.
        val refusedForGood = vm.prefs.contactsAsked && activity?.shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CONTACTS) == false
        if (refusedForGood) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            vm.prefs.contactsAsked = true
            askContacts.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
    @Composable
    fun Suggestions(field: String) {
        if (active != field || typing.isEmpty() || (suggestions.isEmpty() && contactsOk)) return
        SuggestionList(suggestions, contactsOk, onAllow = { allowContacts() }, onPick = { pickRecipient(it) })
    }
    var picking by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val dirty = to != init.to || cc != init.cc || bcc.isNotBlank() || subject != init.subject || body.trim() != (signature + init.body).trim() || files.isNotEmpty()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(com.opensolr.mail.ui.Guard) {
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
            Line(stringResource(R.string.to)) { RecipientField(to, { to = it }, Modifier.weight(1f), toFocus) { if (it) active = "to" else if (active == "to") active = null } }
            Suggestions("to")
            Hairline()
            if (showCc) {
                Line(stringResource(R.string.cc)) { RecipientField(cc, { cc = it }, Modifier.weight(1f)) { if (it) active = "cc" else if (active == "cc") active = null } }
                Suggestions("cc")
                Hairline()
                Line(stringResource(R.string.bcc)) { RecipientField(bcc, { bcc = it }, Modifier.weight(1f)) { if (it) active = "bcc" else if (active == "bcc") active = null } }
                Suggestions("bcc")
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
            BodyField(bodyValue, { bodyValue = it }, stringResource(R.string.message_hint), vm.prefs.textScale / 100f, Modifier.fillMaxWidth(), focus = bodyFocus)
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

/** An address field that keeps its cursor at the end when a suggestion is put in. */
@Composable
private fun RecipientField(value: String, onChange: (String) -> Unit, modifier: Modifier, focus: androidx.compose.ui.focus.FocusRequester? = null, onFocus: (Boolean) -> Unit) {
    val p = LocalPalette.current
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
    if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    Box(modifier.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 11.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = field, onValueChange = { field = it; onChange(it.text) }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
            keyboardOptions = com.opensolr.mail.ui.textKeyboard(true),
            modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).onFocusChanged { onFocus(it.isFocused) },
        )
    }
}

/** The suggestions under an address field: a title, the people, and first of all the way to add the contacts when they are not allowed yet. */
@Composable
private fun SuggestionList(
    list: List<com.opensolr.mail.search.RecipientSuggest.Suggestion>,
    contactsOk: Boolean,
    onAllow: () -> Unit,
    onPick: (com.opensolr.mail.search.RecipientSuggest.Suggestion) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).background(p.paper).border(1.dp, p.hairline)) {
        if (!contactsOk) {
            Row(Modifier.fillMaxWidth().hapticClickable(onClick = onAllow).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).border(1.dp, p.accent, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_contact), null, tint = p.accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.suggest_allow_contacts), style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.accent)
            }
            if (list.isNotEmpty()) Hairline()
        }
        if (list.isNotEmpty()) {
            val history = list.any { !it.fromContacts }
            val contacts = list.any { it.fromContacts }
            Text(
                stringResource(when { history && contacts -> R.string.suggest_history_contacts; contacts -> R.string.suggest_contacts; else -> R.string.suggest_history }),
                style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.muted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            )
            list.forEach { s ->
                Row(Modifier.fillMaxWidth().hapticClickable { onPick(s) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ContactPhoto(s.photo, s.name, s.email)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (s.name.isNotEmpty()) Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.email, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = if (s.name.isEmpty()) p.ink else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.fromContacts) {
                        Spacer(Modifier.width(8.dp))
                        Icon(painterResource(R.drawable.ic_contact), null, tint = p.muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** The contact's own picture when the phone has one for the address, otherwise the same initials as in the mail list. */
@Composable
private fun ContactPhoto(uri: String?, name: String, email: String) {
    val context = LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) {
        androidx.compose.foundation.Image(b, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape))
    } else {
        com.opensolr.mail.ui.Avatar(name, email, 34.dp)
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
