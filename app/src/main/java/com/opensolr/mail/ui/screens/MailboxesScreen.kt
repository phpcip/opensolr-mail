package com.opensolr.mail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.mail.R
import com.opensolr.mail.data.Mailbox
import com.opensolr.mail.data.Role
import com.opensolr.mail.data.View
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.ComposeInit
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.itemMotion
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun roleIcon(role: String?): Int = when (role) {
    "inbox" -> R.drawable.ic_inbox
    "sent" -> R.drawable.ic_sent
    "drafts" -> R.drawable.ic_drafts
    "archive" -> R.drawable.ic_archive
    "junk" -> R.drawable.ic_junk
    "trash" -> R.drawable.ic_trash
    else -> R.drawable.ic_folder
}

fun roleLabel(role: Role): Int = when (role) {
    Role.INBOX -> R.string.all_inboxes
    Role.SENT -> R.string.all_sent
    Role.DRAFTS -> R.string.all_drafts
    Role.ARCHIVE -> R.string.all_archive
    Role.JUNK -> R.string.all_junk
    Role.TRASH -> R.string.all_trash
}

fun isNotes(b: Mailbox) = b.parentId == null && b.role == null && b.name.equals("Notes", true)

@Composable
fun MailboxesScreen(vm: AppViewModel) {
    val accounts by vm.store.accounts.collectAsState()
    val version by vm.db.version.collectAsState()
    var boxes by remember { mutableStateOf<List<Mailbox>>(emptyList()) }
    var unread by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var open by remember { mutableStateOf(vm.prefs.openSections) }
    fun toggle(key: String) {
        open = if (key in open) open - key else open + key
        vm.prefs.openSections = open
    }
    LaunchedEffect(version, accounts) {
        val loaded = withContext(Dispatchers.IO) { vm.db.mailboxes() to vm.db.unreadByRole() }
        boxes = loaded.first
        unread = loaded.second
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.mailboxes)) {
            val allKeys = listOf("unified") + accounts.map { it.key }
            val anyOpen = allKeys.any { it in open }
            IconBtn(if (anyOpen) R.drawable.ic_collapse_all else R.drawable.ic_expand_all, {
                open = if (anyOpen) emptySet() else allKeys.toSet()
                vm.prefs.openSections = open
            })
            IconBtn(R.drawable.ic_search, { vm.go(Screen.Search()) })
            IconBtn(R.drawable.ic_compose, { vm.go(Screen.Compose(ComposeInit())) })
        }
        val listState = com.opensolr.mail.ui.rememberListMemory(vm.prefs, "mailboxes", boxes.isNotEmpty())
        val allLabel = stringResource(R.string.all_accounts)
        val scrollIndex = remember(boxes, accounts, open, allLabel) {
            com.opensolr.mail.ui.ScrollIndex().apply {
                head(allLabel)
                if ("unified" in open) rows(Role.entries.size + 1)
                accounts.forEach { a ->
                    head(a.username)
                    if (a.key in open) {
                        val mine = boxes.filter { it.acc == a.key }
                        rows(mine.count { !isNotes(it) } + if (mine.any { isNotes(it) }) 1 else 0)
                    }
                }
                rows(3)
            }
        }
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "h:unified") { SectionHeader(stringResource(R.string.all_accounts), null, unread["inbox"] ?: 0, "unified" in open) { toggle("unified") } }
            if ("unified" in open) {
                items(Role.entries.toList(), key = { "u:" + it.jmap }) { role ->
                    val v = View.Unified(role)
                    Box(itemMotion()) { BoxRow(
                        icon = roleIcon(role.jmap), label = stringResource(roleLabel(role)),
                        count = if (role == Role.INBOX || role == Role.JUNK) unread[role.jmap] ?: 0 else 0,
                        indent = 0, color = null,
                        menu = BoxMenu(
                            total = boxes.filter { it.role == role.jmap }.sumOf { it.total },
                            canEmpty = role == Role.TRASH || role == Role.JUNK,
                            onRead = { vm.readAll(v) }, onEmpty = { vm.empty(v) },
                        ),
                    ) { vm.home(Screen.List(v)) } }
                }
                item(key = "u:flagged") {
                    Box(itemMotion()) { BoxRow(R.drawable.ic_flag, stringResource(R.string.flagged), 0, 0, null) { vm.home(Screen.List(View.Flagged)) } }
                }
            }
            accounts.forEach { a ->
                val mine = boxes.filter { it.acc == a.key }
                val inboxUnread = mine.filter { it.role == "inbox" }.sumOf { it.unread }
                item(key = "h:" + a.key) { SectionHeader(a.username, Color(a.color), inboxUnread, a.key in open) { toggle(a.key) } }
                if (a.key in open) {
                    val notes = mine.firstOrNull { isNotes(it) }
                    val ordered = tree(mine.filterNot { isNotes(it) })
                    items(ordered, key = { "${a.key}:${it.first.id}" }) { (b, depth) ->
                        val v = View.Box(a.key, b.id)
                        Box(itemMotion()) { BoxRow(
                            roleIcon(b.role), b.name, b.unread, depth, Color(a.color),
                            menu = BoxMenu(b.total, b.role == Role.TRASH.jmap || b.role == Role.JUNK.jmap, { vm.readAll(v) }, { vm.empty(v) }),
                        ) { vm.home(Screen.List(v)) } }
                    }
                    if (notes != null) item(key = "n:" + a.key) {
                        Box(itemMotion()) { BoxRow(R.drawable.ic_notes, stringResource(R.string.notes), 0, 0, Color(a.color)) { vm.go(Screen.Notes(a.key)) } }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)); Hairline() }
            item { BoxRow(R.drawable.ic_settings, stringResource(R.string.settings), 0, 0, null) { vm.go(Screen.Settings) } }
            item { Spacer(Modifier.height(bottomInset())) }
        }
        FastScroller(listState, scrollIndex)
        }
    }
}

/** Mailboxes in tree order: special ones first, then by sort order and name, children under their parent. */
private fun tree(list: List<Mailbox>): List<Pair<Mailbox, Int>> {
    val byParent = list.groupBy { it.parentId }
    val rolesOrder = listOf("inbox", "drafts", "sent", "archive", "junk", "trash")
    val out = ArrayList<Pair<Mailbox, Int>>()
    fun walk(parent: String?, depth: Int) {
        byParent[parent].orEmpty().sortedWith(
            compareBy<Mailbox> { if (it.role in rolesOrder) rolesOrder.indexOf(it.role) else 100 }.thenBy { it.sortOrder }.thenBy { it.name.lowercase() }
        ).forEach { b ->
            out += b to depth
            if (depth < 6) walk(b.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

/** A foldable section of the mailbox list; folded until tapped, and remembered. */
@Composable
private fun SectionHeader(label: String, color: Color?, unread: Int, open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(p.accent.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .clickable { com.opensolr.mail.ui.Haptics.tick(view, false); onToggle() }
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown else androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null, tint = p.accent, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (unread > 0) Text(unread.toString(), style = MaterialTheme.typography.labelSmall, color = p.onAccentFill, modifier = Modifier.background(p.accentFill).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/** What a long press on a mailbox offers: mark everything read, and for Trash and Junk, empty it for good. */
private class BoxMenu(val total: Int, val canEmpty: Boolean, val onRead: () -> Unit, val onEmpty: () -> Unit)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BoxRow(icon: Int, label: String, count: Int, indent: Int, color: Color?, menu: BoxMenu? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = { com.opensolr.mail.ui.Haptics.tap(view); onClick() },
                    onLongClick = if (menu == null) null else ({ com.opensolr.mail.ui.Haptics.tick(view, true); confirm = false; open = true }),
                )
                .padding(start = (16 + indent * 18).dp, end = 16.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, tint = color ?: p.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (count > 0) {
                Box(Modifier.background(p.chip).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = p.ink)
                }
            }
        }
        if (menu != null) {
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = p.paper, offset = androidx.compose.ui.unit.DpOffset((16 + indent * 18).dp, 0.dp)) {
                androidx.compose.material3.DropdownMenuItem(
                    leadingIcon = { Icon(painterResource(R.drawable.ic_read_all), null, tint = p.ink, modifier = Modifier.size(20.dp)) },
                    text = { Text(stringResource(R.string.mark_all_read), style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = p.ink) },
                    onClick = { com.opensolr.mail.ui.Haptics.tick(view, true); open = false; menu.onRead() },
                )
                if (menu.canEmpty) {
                    // Emptying destroys for good, so it takes a second tap on the same item.
                    androidx.compose.material3.DropdownMenuItem(
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, tint = if (confirm) p.accent else p.ink, modifier = Modifier.size(20.dp)) },
                        text = {
                            Text(
                                if (confirm) stringResource(R.string.empty_box_confirm, String.format(java.util.Locale.US, "%,d", menu.total)) else stringResource(R.string.empty_box, label),
                                style = MaterialTheme.typography.bodyLarge, fontWeight = if (confirm) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                                color = if (confirm) p.accent else p.ink,
                            )
                        },
                        onClick = {
                            com.opensolr.mail.ui.Haptics.tick(view, true)
                            if (!confirm) confirm = true else { open = false; confirm = false; menu.onEmpty() }
                        },
                    )
                }
            }
        }
    }
}
