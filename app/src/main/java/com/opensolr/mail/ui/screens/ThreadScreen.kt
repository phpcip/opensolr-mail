package com.opensolr.mail.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.opensolr.mail.ui.Haptics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.core.content.FileProvider
import com.opensolr.mail.R
import com.opensolr.mail.data.Mailbox
import com.opensolr.mail.data.Message
import com.opensolr.mail.jmap.Jmap
import com.opensolr.mail.jmap.Replies
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.ComposeInit
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.MailWebView
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtDate
import com.opensolr.mail.ui.fmtSize
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.scrollMark
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ThreadScreen(vm: AppViewModel, acc: String, threadId: String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by vm.db.version.collectAsState()
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var arrivedNew by remember(threadId) { mutableStateOf<Set<String>?>(null) }
    val bodies = remember { mutableStateMapOf<String, Message>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Compact headers unless the reader unfolded them; the choice is kept for every message and every later visit.
    var details by remember { mutableStateOf(vm.prefs.headerDetails) }
    val images = remember { mutableStateMapOf<String, Boolean>() }
    var moving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<com.opensolr.mail.data.Attachment?>(null) }
    val clipboard = remember { context.getSystemService(android.content.ClipboardManager::class.java) }
    fun copy(text: String) {
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("", text))
        vm.toast(R.string.copied)
    }
    val saveAs = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val a = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri == null) return@rememberLauncherForActivityResult
        val a0 = vm.store.get(acc) ?: return@rememberLauncherForActivityResult
        scope.launch(com.opensolr.mail.ui.Guard) {
            try {
                val tmp = File(context.cacheDir, "attachments/save.tmp").apply { parentFile?.mkdirs() }
                Jmap(context, a0).download(a.blobId, a.name, a.type, tmp)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                }
                vm.toast(R.string.saved)
            } catch (e: Exception) {
                vm.message = e.message
            }
        }
    }
    val account = vm.store.get(acc)
    val zoomed = remember(threadId) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    var loadedOnce by remember(threadId) { mutableStateOf(false) }
    LaunchedEffect(threadId, version) { com.opensolr.mail.ui.guarded {
            // Newest message on top, like the list the conversation was opened from.
            // A failed read still ends the loading state, so the screen never spins forever.
            val list = try { vm.openThread(acc, threadId).sortedByDescending { it.received } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { loadedOnce = true; throw e }
            // The messages that were new when the conversation opened stay marked as new while it is open.
            if (arrivedNew == null) arrivedNew = list.filter { !it.seen }.map { it.id }.toSet()
            messages = list
            loadedOnce = true
            if (list.isNotEmpty() && expanded.isEmpty()) {
                val remembered = vm.threadOpen[acc + ":" + threadId]
                if (remembered != null) remembered.forEach { expanded[it] = true }
                else {
                    list.forEach { m -> if (!m.seen) expanded[m.id] = true }
                    expanded[list.first().id] = true
                }
            }
            val unread = list.filter { !it.seen }.map { it.id }
            if (unread.isNotEmpty()) vm.markThreadRead(acc, unread)
        }
    }
    LaunchedEffect(expanded.toMap()) {
        if (messages.isNotEmpty()) vm.threadOpen[acc + ":" + threadId] = expanded.filterValues { it }.keys.toSet()
    }
    LaunchedEffect(messages, expanded.toMap()) { com.opensolr.mail.ui.guarded {
            val missing = messages.filter { expanded[it.id] == true && bodies[it.id] == null }
            if (missing.isNotEmpty()) vm.bodies(missing).forEach { bodies[it.id] = it }
        }
    }

    val latest = messages.firstOrNull()
    val subject = messages.lastOrNull()?.subject.orEmpty()

    fun reply(kind: Replies.Kind, target: Message? = null) {
        val m = target ?: latest ?: return
        scope.launch(com.opensolr.mail.ui.Guard) {
            val full = bodies[m.id] ?: vm.body(m)
            val d = withContext(Dispatchers.IO) { Replies.build(kind, full, vm.db.identities(acc)) }
            val att = kind == Replies.Kind.FORWARD
            vm.go(
                Screen.Compose(
                    ComposeInit(
                        acc = acc, identityId = d.identity?.id,
                        to = d.to.joinToString(", ") { it.formatted() }, cc = d.cc.joinToString(", ") { it.formatted() },
                        subject = d.subject, body = d.quote, inReplyTo = d.inReplyTo, references = d.references,
                        answeredId = if (att) null else m.id,
                    )
                )
            )
        }
    }

    // The action waiting for its confirmation: title, text and button, and what it does.
    var ask by remember { mutableStateOf<Pair<Triple<Int, Int, Int>, () -> Unit>?>(null) }

    /** Messages of the conversation that are not only copies in Sent or Drafts: what archive and delete act on. */
    fun actionable(): List<String> {
        val boxes = vm.db.mailboxes(acc).associateBy { it.id }
        return messages.filter { m -> m.mailboxIds.any { boxes[it]?.role !in setOf("sent", "drafts") } || m.mailboxIds.isEmpty() }.map { it.id }
            .ifEmpty { messages.map { it.id } }
    }

    Column(Modifier.fillMaxSize()) {
        // Until the conversation is read from the phone, the bar stays without a title rather than saying (No subject).
        TopBar(if (!loadedOnce) "" else subject.ifBlank { stringResource(R.string.no_subject) }, onBack = { vm.back() }) {
            if (messages.size > 1) {
                val anyClosed = messages.any { expanded[it.id] != true }
                IconBtn(if (anyClosed) R.drawable.ic_expand_all else R.drawable.ic_collapse_all, {
                    messages.forEach { expanded[it.id] = anyClosed }
                })
            }
        }
        val threadScroll = com.opensolr.mail.ui.rememberScrollMemory("thread_" + acc + "_" + threadId, { vm.positions["thread_" + acc + "_" + threadId]?.first ?: 0 }, { v -> vm.positions["thread_" + acc + "_" + threadId] = v to 0 })
        val threadMarks = remember { com.opensolr.mail.ui.ScrollMarks() }
        Box(Modifier.weight(1f)) {
            // Opened from a notification while the app was asleep: a loading state until the conversation is there.
            if (!loadedOnce) {
                Box(Modifier.fillMaxSize().zIndex(1f).background(p.paper), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(32.dp), color = p.accent, trackColor = p.hairline, strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
                    }
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(com.opensolr.mail.ui.LocalScrollMarks provides threadMarks) {
            Column(Modifier.fillMaxSize().verticalScroll(threadScroll)) {
            if (account != null) {
                Text(
                    account.username, style = MaterialTheme.typography.labelSmall, color = Color(account.color),
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                )
            }
            // A conversation that cannot be found (deleted, or on an account this phone no longer has) says so instead of an empty page.
            if (loadedOnce && messages.isEmpty()) {
                Text(stringResource(R.string.thread_gone), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(24.dp))
            }
            messages.forEach { m ->
                val open = expanded[m.id] == true
                val sender = m.from.firstOrNull()?.label.orEmpty()
                Box(Modifier.scrollMark(threadMarks, m.id, sender + "\n" + fmtDate(m.received)).padding(top = 8.dp)) {
                    MessageHeader(m, open, details, isNew = arrivedNew?.contains(m.id) == true, onCopy = { copy(it) }, onDetails = { details = !details; vm.prefs.headerDetails = details }) { expanded[m.id] = !open }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = open,
                    enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(240)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
                    exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
                ) {
                    Column {
                        if (details) Row(Modifier.fillMaxWidth().background(p.headFill).drawBehind { drawRect(p.accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                            IconBtn(R.drawable.ic_reply, { reply(Replies.Kind.REPLY, m) })
                            IconBtn(R.drawable.ic_reply_all, { reply(Replies.Kind.REPLY_ALL, m) })
                            IconBtn(R.drawable.ic_forward, { reply(Replies.Kind.FORWARD, m) })
                            IconBtn(R.drawable.ic_copy, {
                                val full = bodies[m.id]
                                copy(full?.bodyText?.takeIf { it.isNotBlank() } ?: full?.bodyHtml?.let { com.opensolr.mail.jmap.Html.toText(it) } ?: m.preview)
                            })
                        }
                        val full = bodies[m.id]
                        if (full == null) {
                            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(16.dp))
                        } else {
                            // The message itself shows; the quoted history under it is folded, one tap opens it.
                            val quoted = stringResource(R.string.show_quoted)
                            val hideQuoted = stringResource(R.string.hide_quoted)
                            val html = remember(full.bodyHtml, full.bodyText, quoted) {
                                val raw = full.bodyHtml.orEmpty()
                                if (raw.isBlank()) com.opensolr.mail.jmap.Html.fromTextFolded(full.bodyText ?: m.preview, quoted, hideQuoted)
                                else com.opensolr.mail.jmap.Html.foldQuotes(raw, quoted, hideQuoted)
                            }
                            // Any picture fetched from the web: <img src>, srcset, background="...", CSS url(...), with or without http:
                            val hasRemote = remember(html) { REMOTE_IMAGE.containsMatchIn(html) }
                            // Pictures can be allowed for one message, or for every message from its sender until stopped.
                            val sender = m.sender?.email.orEmpty().trim().lowercase()
                            val trusted = sender.isNotEmpty() && sender in vm.keySet(IMAGE_SENDERS)
                            val allow = vm.prefs.remoteImages || trusted || images[m.id] == true
                            if (hasRemote && !vm.prefs.remoteImages) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (!allow) ImagePill(R.drawable.ic_image, stringResource(R.string.load_images)) { images[m.id] = true }
                                    if (sender.isNotEmpty()) ImagePill(
                                        if (trusted) R.drawable.ic_eye_off else R.drawable.ic_eye,
                                        stringResource(if (trusted) R.string.stop_images_sender else R.string.always_images_sender),
                                    ) {
                                        if (trusted) images.remove(m.id)
                                        vm.toggleKey(IMAGE_SENDERS, sender)
                                    }
                                }
                            }
                            // At most a screen tall: inside it the message moves freely in every direction at once, as the
                            // finger goes (zoomed or not); at its top or bottom edge the drag carries on to the conversation.
                            val maxBody = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 190).coerceAtLeast(240).dp
                            // Read normally the message is as tall as it is and scrolls with the conversation; zoomed in, it
                            // is held to a screen and pans freely inside.
                            val isZoomed = zoomed[m.id] == true
                            MailWebView(
                                html, acc, full.attachments, allow,
                                if (isZoomed) Modifier.fillMaxWidth().heightIn(min = 40.dp, max = maxBody) else Modifier.fillMaxWidth().heightIn(min = 40.dp),
                                onZoomed = { z -> zoomed[m.id] = z },
                                onEdgeDrag = { dy -> threadScroll.dispatchRawDelta(-dy) },
                                onEdgeFling = { vy -> scope.launch(com.opensolr.mail.ui.Guard) { threadScroll.animateScrollBy(-vy * 0.35f) } },
                            )
                            val files = full.attachments.filter { !it.inline || it.cid == null }
                            if (files.isNotEmpty()) Attachments(files, onSave = { a ->
                                // Download only: the file goes to Downloads, the system notification opens it later.
                                val a0 = vm.store.get(acc) ?: return@Attachments
                                scope.launch(com.opensolr.mail.ui.Guard) {
                                    // Already in Downloads: not downloaded again; the notification opens the file that is there.
                                    val there = com.opensolr.mail.ui.AttachmentDownloads.already(context, a0, a)
                                    if (there != null) {
                                        com.opensolr.mail.sync.Notifier.alreadyDownloaded(context, com.opensolr.mail.ui.AttachmentDownloads.fileName(a), there, com.opensolr.mail.ui.AttachmentDownloads.typeOf(a))
                                        vm.toast(R.string.att_already_text, com.opensolr.mail.ui.AttachmentDownloads.fileName(a))
                                        return@launch
                                    }
                                    vm.toast(R.string.att_downloading, com.opensolr.mail.ui.AttachmentDownloads.fileName(a))
                                    when (val r = com.opensolr.mail.ui.AttachmentDownloads.download(context, a0, a)) {
                                        is com.opensolr.mail.ui.AttachmentDownloads.Result.Done -> vm.toast(R.string.att_saved_downloads, com.opensolr.mail.ui.AttachmentDownloads.fileName(a))
                                        is com.opensolr.mail.ui.AttachmentDownloads.Result.Failed -> vm.message = r.reason
                                    }
                                }
                            }) { a ->
                                // Open: the whole file is downloaded into Downloads first, then that file is opened.
                                val a0 = vm.store.get(acc) ?: return@Attachments
                                scope.launch(com.opensolr.mail.ui.Guard) {
                                    // A copy already in Downloads opens at once, without downloading it again.
                                    val there = com.opensolr.mail.ui.AttachmentDownloads.already(context, a0, a)
                                    if (there != null) {
                                        if (!com.opensolr.mail.ui.AttachmentDownloads.open(context, there, com.opensolr.mail.ui.AttachmentDownloads.typeOf(a))) vm.toast(R.string.att_no_app_saved, com.opensolr.mail.ui.AttachmentDownloads.fileName(a))
                                        return@launch
                                    }
                                    vm.toast(R.string.att_downloading, com.opensolr.mail.ui.AttachmentDownloads.fileName(a))
                                    when (val r = com.opensolr.mail.ui.AttachmentDownloads.download(context, a0, a)) {
                                        is com.opensolr.mail.ui.AttachmentDownloads.Result.Done -> {
                                            if (com.opensolr.mail.ui.AttachmentDownloads.open(context, r.uri, r.type)) vm.message = null
                                            else vm.toast(R.string.att_no_app_saved, com.opensolr.mail.ui.AttachmentDownloads.fileName(a))
                                        }
                                        is com.opensolr.mail.ui.AttachmentDownloads.Result.Failed -> vm.message = r.reason
                                    }
                                }
                            }
                        }
                    }
                }
                Hairline()
            }
            Spacer(Modifier.height(16.dp))
        }
            }
            FastScroller(threadScroll, threadMarks)
        }
        Column(Modifier.fillMaxWidth().background(p.dockFill)) {
            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(bottom = bottomInset()).height(56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(R.drawable.ic_reply, { reply(Replies.Kind.REPLY) })
                IconBtn(R.drawable.ic_reply_all, { reply(Replies.Kind.REPLY_ALL) })
                IconBtn(R.drawable.ic_forward, { reply(Replies.Kind.FORWARD) })
                IconBtn(R.drawable.ic_flag, {
                    latest?.let { vm.setFlagged(acc, listOf(it.id), !it.flagged) }
                }, tint = if (latest?.flagged == true) p.accent else null)
                IconBtn(R.drawable.ic_unread, { vm.setSeen(acc, messages.map { it.id }, false); vm.back() })
                IconBtn(R.drawable.ic_move, { moving = true }, strong = true)
                // Spam, Archive and Delete ask first and say what they will do.
                IconBtn(R.drawable.ic_junk, { ask = Triple(R.string.one_junk_title, R.string.one_junk_text, R.string.tool_junk) to { vm.reportJunk(acc, actionable()); vm.toast(R.string.reported_junk); vm.back() } }, strong = true)
                IconBtn(R.drawable.ic_archive, { ask = Triple(R.string.one_archive_title, R.string.one_archive_text, R.string.tool_archive) to { vm.archive(acc, actionable()); vm.back() } }, strong = true)
                IconBtn(R.drawable.ic_delete, {
                    // In Trash or Spam a delete is for good.
                    val boxes = vm.db.mailboxes(acc).associateBy { it.id }
                    val binned = messages.isNotEmpty() && messages.all { m -> m.mailboxIds.any { boxes[it]?.role == "trash" || boxes[it]?.role == "junk" } }
                    ask = Triple(R.string.one_delete_title, if (binned) R.string.one_delete_forever_text else R.string.one_delete_text, R.string.delete) to { vm.delete(acc, actionable()); vm.back() }
                }, strong = true)
            }
        }
    }

    ask?.let { (texts, action) ->
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(texts.first)) },
            text = { Text(stringResource(texts.second), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { Haptics.heavy(view); ask = null; action() }) {
                    Text(stringResource(texts.third), color = p.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { Haptics.tick(view, false); ask = null }) { Text(stringResource(R.string.cancel), color = p.ink, fontWeight = FontWeight.Bold) }
            },
            containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.ink,
        )
    }

    if (moving) {
        val boxes = remember { vm.db.mailboxes(acc).filterNot { isNotes(it) } }
        MovePicker(boxes, onPick = { b -> moving = false; vm.move(acc, actionable(), b.id); vm.back() }, onDismiss = { moving = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageHeader(m: Message, open: Boolean, details: Boolean, isNew: Boolean, onCopy: (String) -> Unit, onDetails: () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    // Each message opens on its own tinted band with an edge, the accent one when it is open, so where one
    // message ends and the next begins reads at a glance in both themes. A message that was new gets the
    // accent wash, a dot, a bold sender and the date in the accent.
    val rim = if (open || isNew) p.accent else p.headRim
    Column(
        Modifier.fillMaxWidth().background(if (isNew) com.opensolr.mail.ui.unreadFill() else p.headFill)
            .drawBehind { drawRect(rim, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
            .hapticClickable(onClick = onClick).padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isNew) { com.opensolr.mail.ui.UnreadDot(); Spacer(Modifier.width(8.dp)) }
            Text(
                m.sender?.label ?: stringResource(R.string.no_sender), style = MaterialTheme.typography.titleSmall, color = p.ink,
                fontWeight = if (isNew) FontWeight.ExtraBold else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(fmtDate(m.received), style = MaterialTheme.typography.bodySmall, color = if (isNew) p.accent else p.muted, fontWeight = if (isNew) FontWeight.Bold else null)
            // An open message shows only the sender and the date; the chevron unfolds From, To and Cc.
            if (open) {
                val turn by androidx.compose.animation.core.animateFloatAsState(if (details) 180f else 0f, label = "details")
                Box(
                    Modifier.padding(start = 6.dp).size(32.dp).hapticClickable(onClick = onDetails),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_down),
                        contentDescription = stringResource(if (details) R.string.cd_hide_details else R.string.cd_show_details),
                        tint = p.muted, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
                    )
                }
            }
        }
        if (open && details) {
            Spacer(Modifier.height(6.dp))
            AddressLine(stringResource(R.string.from), m.from, onCopy)
            AddressLine(stringResource(R.string.to), m.to, onCopy)
            AddressLine(stringResource(R.string.cc), m.cc, onCopy)
        } else if (!open) {
            Text(m.preview, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Every name and every address on its own, each copied with a tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressLine(label: String, list: List<com.opensolr.mail.data.Address>, onCopy: (String) -> Unit) {
    if (list.isEmpty()) return
    val p = LocalPalette.current
    // Two people shown; the rest behind Show all, folded back with Hide.
    var all by androidx.compose.runtime.saveable.rememberSaveable(label, list.size) { mutableStateOf(false) }
    val shown = if (all || list.size <= 2) list else list.take(2)
    Row(Modifier.padding(top = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.width(44.dp).padding(top = 5.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { a ->
                if (a.name.isNotBlank() && !a.name.trim().equals(a.email.trim(), true)) CopyPill(a.name, strong = true) { onCopy(a.name) }
                CopyPill(a.email, strong = false) { onCopy(a.email) }
            }
            if (list.size > 2) {
                Text(
                    if (all) stringResource(R.string.hide_all) else stringResource(R.string.show_all_people, list.size),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = p.accent,
                    modifier = Modifier.hapticClickable { all = !all }.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** A small pill for the remote-picture choices: a line icon and a short label, in the accent. */
@Composable
private fun ImagePill(icon: Int, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.background(p.pillFill, RoundedCornerShape(2.dp)).border(1.dp, p.headRim, RoundedCornerShape(2.dp))
            .hapticClickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = p.accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.accent, maxLines = 1)
    }
}

@Composable
private fun CopyPill(text: String, strong: Boolean, onCopy: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = if (strong) p.ink else p.muted,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(p.pillFill, RoundedCornerShape(2.dp)).border(1.dp, p.headRim, RoundedCornerShape(2.dp)).hapticClickable(onClick = onCopy).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Attachments(files: List<com.opensolr.mail.data.Attachment>, onSave: (com.opensolr.mail.data.Attachment) -> Unit, onOpen: (com.opensolr.mail.data.Attachment) -> Unit) {
    val p = LocalPalette.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        files.forEach { a ->
            Row(
                Modifier.background(p.pillFill, RoundedCornerShape(2.dp)).border(1.dp, p.headRim, RoundedCornerShape(2.dp))
                    .hapticClickable { onOpen(a) }.padding(start = 8.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.opensolr.mail.ui.FileTypeIcon(a.name, a.type)
                Spacer(Modifier.width(7.dp))
                // Cut in the middle, so the end of the name and its extension always show.
                com.opensolr.mail.ui.MiddleEllipsisName(a.name.ifBlank { a.type }, MaterialTheme.typography.labelSmall, p.ink, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(fmtSize(a.size), style = MaterialTheme.typography.labelSmall, color = p.muted)
                Spacer(Modifier.width(4.dp))
                // The download is a button of its own, bordered, next to the name that opens the file.
                Box(
                    Modifier.size(28.dp).background(p.buttonFill, RoundedCornerShape(2.dp)).border(1.dp, p.headRim, RoundedCornerShape(2.dp))
                        .hapticClickable { onSave(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_download), null, tint = p.accent, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
fun MovePicker(boxes: List<Mailbox>, onPick: (Mailbox) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(p.paper).border(1.dp, p.hairline).heightIn(max = 520.dp)) {
            Text(stringResource(R.string.move_to), style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.padding(16.dp))
            Hairline()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                boxes.forEach { b ->
                    Row(Modifier.fillMaxWidth().hapticClickable { onPick(b) }.padding(horizontal = 16.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(roleIcon(b.role)), null, tint = p.ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(b.name, style = MaterialTheme.typography.bodyLarge, color = p.ink)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().hapticClickable(onClick = onDismiss).padding(16.dp)) {
                Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge, color = p.accent)
            }
        }
    }
}

/** What an attachment really is, and a copy of it in Downloads when no app opens it. */
private object AttachmentFiles {
    /** The type from the file's own first bytes, then its extension, then what the message declared. */
    fun typeOf(f: File, name: String, declared: String): String {
        val head = ByteArray(8)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        fun starts(vararg b: Int) = n >= b.size && b.indices.all { head[it] == b[it].toByte() }
        val sniffed = when {
            starts(0x25, 0x50, 0x44, 0x46) -> "application/pdf"
            starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            starts(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            starts(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            else -> null
        }
        if (sniffed != null) return sniffed
        val ext = name.substringAfterLast('.', "").lowercase()
        val byName = if (ext.isEmpty()) null else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        val clean = declared.substringBefore(';').trim().lowercase()
        return byName ?: clean.takeIf { it.isNotEmpty() && it != "application/octet-stream" } ?: "application/octet-stream"
    }

    fun nameOf(a: com.opensolr.mail.data.Attachment): String = a.name.ifBlank { "attachment" }

    /** The attachment downloaded in full to the app's cache. */
    suspend fun fetch(context: android.content.Context, account: com.opensolr.mail.data.MailAccount, a: com.opensolr.mail.data.Attachment): File {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val f = File(dir, a.blobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + "_" + nameOf(a).replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100))
        if (!f.exists() || f.length() == 0L) Jmap(context, account).download(a.blobId, a.name, a.type, f)
        return f
    }

    /**
     * The file in the phone's Downloads folder: the copy already there when one of the same name and size
     * exists, otherwise a new one. Null before Android 10, where only the save picker can write there.
     */
    fun inDownloads(context: android.content.Context, f: File, name: String, type: String): android.net.Uri? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val store = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            store, arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.SIZE} = ?",
            arrayOf(name, f.length().toString()), null,
        )?.use { c -> if (c.moveToFirst()) return android.content.ContentUris.withAppendedId(store, c.getLong(0)) }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, type)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(store, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("No output")
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Copies the file into the public Downloads folder; false where that needs the system file picker (before Android 10). */
    fun toDownloads(context: android.content.Context, f: File, name: String, type: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, type)
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("No output")
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}

/** Senders whose pictures always load. */
private const val IMAGE_SENDERS = "image_senders"

private val REMOTE_IMAGE = Regex("""(?i)(\b(src|srcset|background|poster)\s*=\s*["']?\s*(https?:)?//)|(url\(\s*["']?\s*(https?:)?//)""")
