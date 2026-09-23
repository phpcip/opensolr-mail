package com.opensolr.mail.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.mail.R
import com.opensolr.mail.data.ThreadRow
import com.opensolr.mail.data.View
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.ComposeInit
import com.opensolr.mail.ui.Haptics
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.RefreshBox
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtDate
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.itemMotion
import com.opensolr.mail.ui.theme.LocalPalette

@Composable
fun viewTitle(vm: AppViewModel, view: View): String = when (view) {
    is View.Unified -> stringResource(roleLabel(view.role))
    View.Flagged -> stringResource(R.string.flagged)
    is View.Box -> androidx.compose.runtime.remember(view) { vm.db.mailbox(view.acc, view.mailboxId)?.name ?: "" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadListScreen(vm: AppViewModel, view: View) {
    val p = LocalPalette.current
    val version by vm.db.version.collectAsState()
    val accounts by vm.store.accounts.collectAsState()
    var limit by remember(view) { mutableIntStateOf(maxOf(PAGE, vm.prefs.scrollOf(when (view) {
        is View.Unified -> "u_" + view.role.jmap
        is View.Box -> "b_" + view.acc + "_" + view.mailboxId
        View.Flagged -> "flagged"
    }).first + PAGE)) }
    var rows by remember(view) { mutableStateOf<List<ThreadRow>>(emptyList()) }
    var pinned by remember(view) { mutableStateOf<List<ThreadRow>>(emptyList()) }
    var loaded by remember(view) { mutableStateOf(false) }
    var exhausted by remember(view) { mutableStateOf(false) }
    var loadingOlder by remember(view) { mutableStateOf(false) }
    var selected by remember(view) { mutableStateOf<Set<ThreadRow>>(emptySet()) }
    val scrollKey = when (view) {
        is View.Unified -> "u_" + view.role.jmap
        is View.Box -> "b_" + view.acc + "_" + view.mailboxId
        View.Flagged -> "flagged"
    }
    val listState = com.opensolr.mail.ui.rememberListMemory(vm.prefs, scrollKey, loaded && rows.isNotEmpty())
    val colors = accounts.associate { it.key to Color(it.color) }
    val multi = accounts.size > 1
    var grouping by remember { mutableStateOf(runCatching { ListGroup.valueOf(vm.prefs.listGroup) }.getOrDefault(ListGroup.DAY)) }
    var groupMenu by remember { mutableStateOf(false) }
    val foldName = "listfold_" + scrollKey + "_" + grouping.name
    var confirmDelete by remember { mutableStateOf<ThreadRow?>(null) }
    val view0 = LocalView.current
    val hidden = vm.hiddenThreads.keys.toSet()
    val pinnedLabel = stringResource(R.string.flagged)
    val groups = remember(rows, pinned, grouping, accounts, hidden, pinnedLabel) {
        val shown = if (hidden.isEmpty()) rows else rows.filterNot { (it.acc + ":" + it.threadId) in hidden }
        val top = if (hidden.isEmpty()) pinned else pinned.filterNot { (it.acc + ":" + it.threadId) in hidden }
        (if (top.isEmpty()) emptyList() else listOf(RowGroup("pinned", pinnedLabel, top))) +
            (if (grouping == ListGroup.NONE) listOf(RowGroup("all", "", shown)) else groupRows(shown, grouping) { k -> accounts.firstOrNull { it.key == k }?.username ?: k })
    }

    // The pinned Flagged section starts folded and remembers being opened, apart from the day groups.
    val pinName = "pinopen_" + scrollKey
    val pinOpen = "open" in vm.keySet(pinName)
    fun folded(key: String): Boolean = when (key) {
        "pinned" -> !pinOpen
        "all" -> false
        else -> vm.isFolded(foldName, key)
    }
    fun toggleGroup(key: String) = if (key == "pinned") vm.toggleKey(pinName, "open") else vm.toggleFold(foldName, key)

    val folds = vm.keySet(foldName)
    val scrollIndex = remember(groups, grouping, folds, pinOpen, loaded) {
        val dayLabel = java.text.SimpleDateFormat("EEE, MM/dd/yyyy", java.util.Locale.getDefault())
        com.opensolr.mail.ui.ScrollIndex().apply {
            groups.forEach { g ->
                if (g.key != "all") head(g.label)
                if (g.key == "all") {
                    // A flat list still has titles to feel: the day each conversation belongs to.
                    var lastDay = Long.MIN_VALUE
                    var label = ""
                    g.rows.forEach { r ->
                        val day = Math.floorDiv(r.received + java.util.TimeZone.getDefault().getOffset(r.received), 86_400_000L)
                        if (day != lastDay) { lastDay = day; label = dayLabel.format(java.util.Date(r.received)) }
                        row(label)
                    }
                } else if (!folded(g.key)) rows(g.rows.size)
            }
            if (loaded && rows.isEmpty() && pinned.isEmpty()) row()
            row()
        }
    }

    LaunchedEffect(view, version, limit) {
        // Flagged conversations are pinned above everything else in every view but Flagged itself.
        if (view == View.Flagged) {
            rows = vm.threads(view, limit)
        } else {
            pinned = vm.threads(view, 500, flagged = true)
            rows = vm.threads(view, limit, flagged = false)
        }
        loaded = true
    }
    // A conversation just flagged is pinned above everything: bring the top into view so it is seen going there.
    var pinnedSeen by remember(view) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(pinned) {
        val now = pinned.map { it.acc + ":" + it.threadId }.toSet()
        val before = pinnedSeen
        pinnedSeen = now
        if (before != null && (now - before).isNotEmpty()) runCatching { listState.animateScrollToItem(0) }
    }
    val atEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 3 } == true } }
    LaunchedEffect(atEnd, rows.size) {
        // More is loaded only when the reader scrolled to the end, never on its own: with folded groups the list is short and would otherwise pull the whole history.
        if (!atEnd || !loaded || loadingOlder || !listState.canScrollBackward) return@LaunchedEffect
        if (rows.size >= limit) {
            limit += PAGE
        } else if (!exhausted) {
            loadingOlder = true
            if (vm.loadOlder(view) == 0) exhausted = true
            loadingOlder = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isEmpty()) {
            TopBar(viewTitle(vm, view), onBack = { vm.go(Screen.Mailboxes) }) {
                if (groups.any { it.key != "all" }) {
                    val anyOpen = groups.any { it.key != "all" && !folded(it.key) }
                    IconBtn(if (anyOpen) R.drawable.ic_collapse_all else R.drawable.ic_expand_all, {
                        vm.foldAll(foldName, anyOpen)
                        vm.setKeySet(pinName, if (anyOpen) emptySet() else setOf("open"))
                    })
                }
                Box {
                    IconBtn(R.drawable.ic_group, { groupMenu = true }, tint = if (grouping != ListGroup.NONE) p.accent else null)
                    DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }, containerColor = p.paper) {
                        Text(stringResource(R.string.group_by), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        ListGroup.entries.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(stringResource(g.label), style = MaterialTheme.typography.bodyLarge, fontWeight = if (g == grouping) FontWeight.Bold else FontWeight.Medium, color = if (g == grouping) p.accent else p.ink) },
                                onClick = { groupMenu = false; grouping = g; vm.prefs.listGroup = g.name },
                            )
                        }
                    }
                }
                if (view !is View.Flagged && (rows.any { it.unread } || pinned.any { it.unread })) IconBtn(R.drawable.ic_read_all, { vm.readAll(view) })
                IconBtn(R.drawable.ic_filters, { vm.go(Screen.Search("filters")) })
                IconBtn(R.drawable.ic_search, { vm.go(Screen.Search()) })
                IconBtn(R.drawable.ic_compose, { vm.go(Screen.Compose(ComposeInit())) })
            }
        } else {
            TopBar(stringResource(R.string.selected_n, selected.size), onBack = { selected = emptySet() })
        }
        RefreshBox(refreshing = vm.busy, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                groups.forEach { g ->
                if (g.key != "all") item(key = "g:" + g.key) {
                    Box(itemMotion()) { ListGroupHeader(g.label, g.rows.size, !folded(g.key)) { toggleGroup(g.key) } }
                }
                if (!folded(g.key)) items(g.rows, key = { it.acc + ":" + it.threadId }) { r ->
                  Column(itemMotion()) {
                    SwipeRow(
                        key = r,
                        onDelete = { if (vm.prefs.confirmSwipeDelete) confirmDelete = r else vm.deleteWithUndo(r, view) },
                        onFlag = { vm.toggleFlagWithUndo(r) },
                        enabled = selected.isEmpty(),
                    ) {
                        ThreadRowView(
                            r, stripe = if (multi) colors[r.acc] else null, selected = r in selected,
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (r in selected) selected - r else selected + r
                                else vm.go(Screen.Thread(r.acc, r.threadId))
                            },
                            onLongClick = { selected = selected + r },
                        )
                    }
                    Hairline()
                  }
                }
                }
                if (loaded && rows.isEmpty() && pinned.isEmpty()) item {
                    Text(
                        stringResource(if (vm.busy) R.string.loading else R.string.empty_view), style = MaterialTheme.typography.bodyMedium,
                        color = p.muted, modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                item { Spacer(Modifier.height(bottomInset() + 8.dp)) }
            }
            FastScroller(listState, scrollIndex)
        }
        confirmDelete?.let { r ->
            // A swipe to the left never deletes on its own: it asks first, and the undo bar follows.
            val forever = when (view) {
                is View.Unified -> view.role == com.opensolr.mail.data.Role.TRASH || view.role == com.opensolr.mail.data.Role.JUNK
                is View.Box -> vm.db.mailbox(view.acc, view.mailboxId)?.role.let { it == "trash" || it == "junk" }
                View.Flagged -> false
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(stringResource(R.string.confirm_delete_title)) },
                text = { Text(stringResource(if (forever) R.string.confirm_delete_forever else R.string.confirm_delete_trash)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { Haptics.heavy(view0); confirmDelete = null; vm.deleteWithUndo(r, view) }) {
                        Text(stringResource(R.string.delete), color = p.accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { Haptics.tick(view0, false); confirmDelete = null }) { Text(stringResource(R.string.cancel), color = p.ink) }
                },
                containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
            )
        }
        if (selected.isNotEmpty()) {
            // Which bin this view is: Trash and Junk offer the way back to the Inbox.
            val binRole = when (view) {
                is View.Unified -> view.role.jmap.takeIf { it == "trash" || it == "junk" }
                is View.Box -> vm.db.mailbox(view.acc, view.mailboxId)?.role?.takeIf { it == "trash" || it == "junk" }
                View.Flagged -> null
            }
            val anyUnread = selected.any { it.unread }
            val anyUnflagged = selected.any { !it.flagged }
            SelectionBar(
                onRead = { run(vm, selected, null) { acc, ids -> vm.setSeen(acc, ids, anyUnread) }; selected = emptySet() },
                readIcon = if (anyUnread) R.drawable.ic_check else R.drawable.ic_unread,
                onFlag = { run(vm, selected, view) { acc, ids -> vm.setFlagged(acc, ids, anyUnflagged) }; selected = emptySet() },
                onArchive = { run(vm, selected, view) { acc, ids -> vm.archive(acc, ids) }; selected = emptySet() },
                onDelete = { run(vm, selected, view) { acc, ids -> vm.delete(acc, ids) }; selected = emptySet() },
                onForward = { vm.forwardSelected(selected.toList()); selected = emptySet() },
                restoreLabel = when (binRole) {
                    "junk" -> R.string.not_junk
                    "trash" -> R.string.move_to_inbox
                    else -> null
                },
                onRestore = {
                    val notJunk = binRole == "junk"
                    run(vm, selected, view) { acc, ids -> vm.restoreToInbox(acc, ids, notJunk) }
                    selected = emptySet()
                },
            )
        }
    }
}

/** Applies an action to every message of the selected conversations, grouped per account. */
private fun run(vm: AppViewModel, rows: Set<ThreadRow>, view: View?, action: (String, List<String>) -> Unit) {
    vm.viewModelScopeLaunch {
        rows.groupBy { it.acc }.forEach { (acc, rs) -> action(acc, rs.flatMap { vm.threadIds(it, view) }) }
    }
}

@Composable
private fun SelectionBar(onRead: () -> Unit, readIcon: Int, onFlag: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onForward: () -> Unit, restoreLabel: Int?, onRestore: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.dockFill)) {
        Hairline()
        // In Trash and Junk the first thing offered is the way back to the Inbox.
        if (restoreLabel != null) {
            com.opensolr.mail.ui.AccentButton(stringResource(restoreLabel), onRestore, Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = bottomInset()).height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(readIcon, onRead)
            IconBtn(R.drawable.ic_flag, onFlag)
            IconBtn(R.drawable.ic_forward, onForward)
            IconBtn(R.drawable.ic_archive, onArchive, strong = true)
            IconBtn(R.drawable.ic_delete, onDelete, strong = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRowView(r: ThreadRow, stripe: Color?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val bg = if (selected) p.accent.copy(alpha = 0.16f).compositeOver(p.paper) else if (r.flagged) p.flagFill else p.paper
    // A conversation of several messages carries the edges of the cards under it, like a stack.
    Column(Modifier.fillMaxWidth().background(bg)) {
    Row(
        Modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = { Haptics.tick(view, true); onLongClick() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (selected) 5.dp else 3.dp).height(68.dp).background(if (selected) p.accentFill else stripe ?: Color.Transparent))
        Spacer(Modifier.width(if (selected) 7.dp else 9.dp))
        // A selected conversation trades its initials for a filled tick, so a selection reads at a glance.
        if (selected) {
            Box(Modifier.size(34.dp).background(p.accentFill, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_check), null, tint = p.onAccentFill, modifier = Modifier.size(22.dp))
            }
        } else {
            com.opensolr.mail.ui.Avatar(if (r.fromName == r.fromEmail) "" else r.fromName, r.fromEmail)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.unread) {
                    Box(Modifier.size(7.dp).background(p.accent, androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(5.dp))
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.senders.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Medium,
                        color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    com.opensolr.mail.ui.CountBadge(r.count)
                }
                Spacer(Modifier.width(6.dp))
                Text(fmtDate(r.received), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.subject.ifBlank { stringResource(R.string.no_subject) }, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (r.unread) FontWeight.Bold else FontWeight.Medium, color = p.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (r.hasAttachment) com.opensolr.mail.ui.AttachBadge()
                if (r.flagged) com.opensolr.mail.ui.FlagBadge()
            }
            Text(r.preview, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    com.opensolr.mail.ui.StackEdges(r.count)
    }
}

private const val PAGE = 60

enum class ListGroup(val label: Int) {
    NONE(R.string.group_none), DAY(R.string.group_day), MONTH(R.string.group_month),
    SENDER(R.string.group_sender), COMPANY(R.string.group_company), ACCOUNT(R.string.group_account),
}

private data class RowGroup(val key: String, val label: String, val rows: List<ThreadRow>)

/** Conversations in groups, in the order the newest of each group appears; one pass over the rows. */
private fun groupRows(rows: List<ThreadRow>, g: ListGroup, accountName: (String) -> String): List<RowGroup> {
    if (g == ListGroup.NONE) return listOf(RowGroup("all", "", rows))
    val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val monthKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
    val dayLabel = java.text.SimpleDateFormat("EEE, MM/dd/yyyy", java.util.Locale.getDefault())
    val monthLabel = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault())
    val out = LinkedHashMap<String, Pair<String, ArrayList<ThreadRow>>>()
    rows.forEach { r ->
        val (key, label) = when (g) {
            ListGroup.DAY -> dayKey.format(r.received) to dayLabel.format(r.received)
            ListGroup.MONTH -> monthKey.format(r.received) to monthLabel.format(r.received).replaceFirstChar { it.uppercase() }
            ListGroup.SENDER -> r.fromEmail.lowercase() to (if (r.fromName.isNotBlank() && !r.fromName.equals(r.fromEmail, true)) "${r.fromName} (${r.fromEmail})" else r.fromEmail)
            ListGroup.COMPANY -> r.fromEmail.substringAfter('@', "").lowercase().let { it to it }
            ListGroup.ACCOUNT -> r.acc to accountName(r.acc)
            ListGroup.NONE -> "all" to ""
        }
        out.getOrPut(key) { label to ArrayList() }.second += r
    }
    return out.map { (k, v) -> RowGroup(k, v.first, v.second) }
}

@Composable
private fun ListGroupHeader(label: String, count: Int, open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
            .background(p.accent.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .clickable { Haptics.tick(view, false); onToggle() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown else androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null, tint = p.accent, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
}

/** iPhone-style swipes: left deletes the conversation, right flags or unflags it and springs back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(key: Any, onDelete: () -> Unit, onFlag: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    // A fresh swipe state whenever the row changes (a flag moves it to the pinned group), so it never lands half open.
    androidx.compose.runtime.key(key) {
    val p = LocalPalette.current
    val view = LocalView.current
    val deleteNow by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val flagNow by androidx.compose.runtime.rememberUpdatedState(onFlag)
    // The swipe acts on release and never settles open, so the row springs back at once and the next swipe always counts.
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.35f },
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.EndToStart -> { Haptics.heavy(view); deleteNow() }
                SwipeToDismissBoxValue.StartToEnd -> { Haptics.heavy(view); flagNow() }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    run {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = enabled,
            enableDismissFromEndToStart = enabled,
            backgroundContent = {
                val toLeft = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
                Box(
                    Modifier.fillMaxSize().background(if (toLeft) p.accentFill else Color(0xFFF5C518)).padding(horizontal = 24.dp),
                    contentAlignment = if (toLeft) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Icon(
                        painterResource(if (toLeft) R.drawable.ic_delete else R.drawable.ic_flag), null,
                        tint = if (toLeft) p.paper else Color(0xFF1A1A1A), modifier = Modifier.size(24.dp),
                    )
                }
            },
            content = { content() },
        )
    }
    }
}
