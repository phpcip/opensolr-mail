package com.opensolr.mail.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
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
import com.opensolr.mail.ui.SelectionBar
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtDate
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.dragSelect
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
    var olderTick by remember(view) { mutableIntStateOf(0) }
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
        val top = (if (hidden.isEmpty()) pinned else pinned.filterNot { (it.acc + ":" + it.threadId) in hidden }).distinctBy { it.acc + ":" + it.threadId }
        // Each conversation shows once: never in the pinned group and in the list below it at the same time.
        val onTop = top.mapTo(HashSet()) { it.acc + ":" + it.threadId }
        val shown = rows.filterNot { (it.acc + ":" + it.threadId).let { k -> k in hidden || k in onTop } }.distinctBy { it.acc + ":" + it.threadId }
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
        // Both lists are read first and shown together: a conversation just flagged is never in both at
        // once, which would give the list the same row twice.
        try {
            if (view == View.Flagged) {
                rows = vm.threads(view, limit)
            } else {
                val top = vm.threads(view, 500, flagged = true)
                val rest = vm.threads(view, limit, flagged = false)
                pinned = top
                rows = rest
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ThreadList", "list read failed", e)
        }
        loaded = true
    }
    // A conversation just flagged is pinned above everything: bring the top into view so it is seen going there.
    var pinnedSeen by remember(view) { mutableStateOf<Set<String>?>(null) }
    // Counted only once the list is read: the first load (coming back from a message too) is not a new flag.
    LaunchedEffect(pinned, loaded) {
        if (!loaded) return@LaunchedEffect
        val now = pinned.map { it.acc + ":" + it.threadId }.toSet()
        val before = pinnedSeen
        pinnedSeen = now
        if (before != null && (now - before).isNotEmpty()) runCatching { listState.animateScrollToItem(0) }
    }
    // The next page comes while the reader is still well above the end (40 rows ahead), and again every time the
    // bottom is reached, by scrolling or by the fast scroller held at the bottom, until the mailbox is read to its end.
    // Only once the reader has moved off the top: an untouched short list (folded groups) never pulls the history.
    LaunchedEffect(view) {
        androidx.compose.runtime.snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val near = listState.canScrollBackward && (last >= total - 40 || !listState.canScrollForward)
            Triple(near && loaded, rows.size, olderTick)
        }.collect { (near, _, _) ->
            if (!near) return@collect
            if (rows.size >= limit) {
                limit += PAGE
            } else if (!exhausted) {
                loadingOlder = true
                val n = vm.loadOlder(view)
                loadingOlder = false
                when {
                    n == 0 -> exhausted = true
                    // A page of older messages from conversations already listed adds no row: ask for the next one.
                    n > 0 -> olderTick++
                    // Fastmail could not be asked: try again shortly, never give up for good.
                    else -> { kotlinx.coroutines.delay(5_000L); olderTick++ }
                }
            }
        }
    }

    // Back clears a selection first; from any other list it returns to All Inboxes; only from All Inboxes it leaves the app.
    val inbox = com.opensolr.mail.data.View.Unified(com.opensolr.mail.data.Role.INBOX)
    androidx.activity.compose.BackHandler(enabled = selected.isNotEmpty() || (vm.stack.size <= 1 && view != inbox)) {
        if (selected.isNotEmpty()) selected = emptySet() else vm.home(Screen.List(inbox))
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
                                onClick = { Haptics.tick(view0, false); groupMenu = false; grouping = g; vm.prefs.listGroup = g.name },
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
            SelectionBar(selected.size, onClear = { selected = emptySet() })
        }
        RefreshBox(refreshing = vm.busy, onRefresh = { vm.refresh() }, modifier = Modifier.weight(1f)) {
            // Long press and drag selects every conversation between, as in Opensolr Photos.
            var dragBase by remember { mutableStateOf<Set<ThreadRow>?>(null) }
            var dragOff by remember { mutableStateOf(false) }
            val byKey = remember(groups) { groups.flatMap { it.rows }.associateBy { it.acc + ":" + it.threadId } }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().dragSelect(
                listState,
                order = { groups.filter { !folded(it.key) }.flatMap { g -> g.rows.map { it.acc + ":" + it.threadId } } },
                onStart = { k ->
                    val r = byKey[k] ?: return@dragSelect
                    Haptics.tick(view0, true)
                    dragBase = selected
                    dragOff = r in selected
                    selected = if (dragOff) selected - r else selected + r
                },
                onRange = { ks ->
                    val base = dragBase ?: return@dragSelect
                    val rs = ks.mapNotNull { byKey[it] }
                    Haptics.tick(view0, false)
                    selected = if (dragOff) base - rs.toSet() else base + rs
                },
                onEnd = { dragBase = null },
            )) {
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
                                if (selected.isNotEmpty()) { Haptics.toggle(view0, r !in selected); selected = if (r in selected) selected - r else selected + r }
                                else vm.go(Screen.Thread(r.acc, r.threadId))
                            },
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
                count = selected.size, inBins = if (binRole != null) selected.size else 0, flagging = anyUnflagged,
                onRead = { run(vm, selected, null) { acc, ids -> vm.setSeen(acc, ids, anyUnread) }; selected = emptySet() },
                readIcon = if (anyUnread) R.drawable.ic_check else R.drawable.ic_unread,
                readLabel = if (anyUnread) R.string.tool_read else R.string.tool_unread,
                onFlag = { run(vm, selected, view) { acc, ids -> vm.setFlagged(acc, ids, anyUnflagged) }; selected = emptySet() },
                onArchive = { run(vm, selected, view) { acc, ids -> vm.archive(acc, ids) }; selected = emptySet() },
                onDelete = {
                    val rows = selected
                    vm.viewModelScopeLaunch { rows.groupBy { it.acc }.forEach { (acc, rs) -> vm.delete(acc, rs.flatMap { vm.deleteIds(it, view) }) } }
                    selected = emptySet()
                },
                onForward = { vm.forwardSelected(selected.toList()); selected = emptySet() },
                onJunk = if (binRole == null) ({ vm.reportJunkRows(selected.toList()); selected = emptySet() }) else null,
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

/** A bulk action waiting for its confirmation: what it does, told before it is done. */
private data class Ask(val title: String, val text: String, val label: String, val action: () -> Unit)

/**
 * The actions on the selected conversations. Every action that changes mail asks first and says what it
 * will do, for one conversation or many; [inBins] is how many of them lie in Trash or Spam, where a delete is for good.
 */
@Composable
internal fun SelectionBar(count: Int, inBins: Int, onRead: () -> Unit, readIcon: Int, readLabel: Int, flagging: Boolean, onFlag: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onForward: () -> Unit, restoreLabel: Int?, onRestore: () -> Unit, onJunk: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val view = LocalView.current
    var ask by remember { mutableStateOf<Ask?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val one = count == 1
    // [many] and [single] are the title and text for several conversations and for one.
    fun confirm(many: Pair<Int, Int>, single: Pair<Int, Int>, label: String, action: () -> Unit) {
        ask = if (one) Ask(ctx.getString(single.first), ctx.getString(single.second), label, action)
        else Ask(ctx.getString(many.first, count), ctx.getString(many.second), label, action)
    }
    val deleteMany = R.string.bulk_delete_title to when { inBins == 0 -> R.string.bulk_delete_text; inBins >= count -> R.string.bulk_delete_forever_text; else -> R.string.bulk_delete_mixed_text }
    val deleteOne = R.string.one_delete_title to if (inBins > 0) R.string.one_delete_forever_text else R.string.one_delete_text
    val reading = readLabel == R.string.tool_read
    val notJunk = restoreLabel == R.string.not_junk
    val readName = stringResource(readLabel)
    val flagName = stringResource(if (flagging) R.string.tool_flag else R.string.tool_unflag)
    val archiveName = stringResource(R.string.tool_archive)
    val junkName = stringResource(R.string.tool_junk)
    val deleteName = stringResource(R.string.delete)
    val restoreName = restoreLabel?.let { stringResource(if (it == R.string.not_junk) R.string.tool_not_junk else R.string.tool_to_inbox) }
    Column(Modifier.fillMaxWidth().background(p.dockFill)) {
        Hairline()
        // One row of labelled icons, the Opensolr Photos dock; in Trash and Junk the way back to the Inbox comes first.
        com.opensolr.mail.ui.ToolRow(
            listOfNotNull(
                restoreName?.let { name -> com.opensolr.mail.ui.Tool(R.drawable.ic_inbox, name, accent = true, onClick = {
                    confirm(if (notJunk) R.string.bulk_notjunk_title to R.string.bulk_notjunk_text else R.string.bulk_inbox_title to R.string.bulk_inbox_text,
                        if (notJunk) R.string.one_notjunk_title to R.string.one_notjunk_text else R.string.one_inbox_title to R.string.one_inbox_text, name, onRestore)
                }) },
                com.opensolr.mail.ui.Tool(readIcon, readName, onClick = {
                    confirm(if (reading) R.string.bulk_read_title to R.string.bulk_read_text else R.string.bulk_unread_title to R.string.bulk_unread_text,
                        if (reading) R.string.one_read_title to R.string.one_read_text else R.string.one_unread_title to R.string.one_unread_text, readName, onRead)
                }),
                com.opensolr.mail.ui.Tool(R.drawable.ic_flag, stringResource(R.string.tool_flag), onClick = {
                    confirm(if (flagging) R.string.bulk_flag_title to R.string.bulk_flag_text else R.string.bulk_unflag_title to R.string.bulk_unflag_text,
                        if (flagging) R.string.one_flag_title to R.string.one_flag_text else R.string.one_unflag_title to R.string.one_unflag_text, flagName, onFlag)
                }),
                com.opensolr.mail.ui.Tool(R.drawable.ic_forward, stringResource(R.string.tool_forward), onClick = onForward),
                com.opensolr.mail.ui.Tool(R.drawable.ic_archive, archiveName, strong = true, onClick = { confirm(R.string.bulk_archive_title to R.string.bulk_archive_text, R.string.one_archive_title to R.string.one_archive_text, archiveName, onArchive) }),
                onJunk?.let { junk -> com.opensolr.mail.ui.Tool(R.drawable.ic_junk, junkName, strong = true, onClick = { confirm(R.string.bulk_junk_title to R.string.bulk_junk_text, R.string.one_junk_title to R.string.one_junk_text, junkName, junk) }) },
                com.opensolr.mail.ui.Tool(R.drawable.ic_delete, deleteName, strong = true, onClick = { confirm(deleteMany, deleteOne, deleteName, onDelete) }),
            ),
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = bottomInset() + 10.dp),
        )
    }
    ask?.let { a ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(a.title) },
            text = { Text(a.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { Haptics.heavy(view); ask = null; a.action() }) {
                    Text(a.label, color = p.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { Haptics.tick(view, false); ask = null }) { Text(stringResource(R.string.cancel), color = p.ink, fontWeight = FontWeight.Bold) }
            },
            containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.ink,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRowView(r: ThreadRow, stripe: Color?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    // Unread stands out plainly: an accent wash behind the row, a large dot, bold sender and subject, the date in the accent.
    val bg = if (selected) p.accent.copy(alpha = 0.16f).compositeOver(p.paper) else if (r.flagged) p.flagFill else if (r.unread) com.opensolr.mail.ui.unreadFill() else p.paper
    // A conversation of several messages is drawn as a stack of cards.
    com.opensolr.mail.ui.StackCard(r.count, bg) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick),
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
                    com.opensolr.mail.ui.UnreadDot()
                    Spacer(Modifier.width(6.dp))
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
                Text(fmtDate(r.received), style = MaterialTheme.typography.bodySmall, color = if (r.unread) p.accent else p.muted, fontWeight = if (r.unread) FontWeight.Bold else null, maxLines = 1)
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
            Text(r.preview, style = MaterialTheme.typography.bodySmall, color = if (r.unread) p.ink else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
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
            .background(p.groupFill, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .border(1.dp, p.groupRim, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
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
internal fun SwipeRow(key: Any, onDelete: () -> Unit, onFlag: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    // A fresh swipe state whenever the row changes (a flag moves it to the pinned group), so it never lands half open.
    androidx.compose.runtime.key(key) {
    val p = LocalPalette.current
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val deleteNow by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val flagNow by androidx.compose.runtime.rememberUpdatedState(onFlag)
    val scope = rememberCoroutineScope()
    // The row stays put for the first stretch, then follows the finger. Two feedbacks on the way: a light one when
    // the swipe is felt, a strong one when it is armed; only a release past the armed point acts, anything less
    // springs back and does nothing.
    val dead = with(density) { 28.dp.toPx() }
    var widthPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var raw by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var stage by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    fun shownOf(r: Float): Float = kotlin.math.sign(r) * (kotlin.math.abs(r) - dead).coerceAtLeast(0f)
    fun stageOf(x: Float): Int = when {
        widthPx <= 0f -> 0
        kotlin.math.abs(x) >= widthPx * 0.40f -> 2
        kotlin.math.abs(x) >= widthPx * 0.20f -> 1
        else -> 0
    }
    fun settle() { raw = 0f; stage = 0; scope.launch(com.opensolr.mail.ui.Guard) { shown.animateTo(0f, androidx.compose.animation.core.tween(180)) } }
    Box(
        Modifier.fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat() }.then(
            if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { raw = 0f; stage = 0 },
                    onDragEnd = {
                        val x = shown.value
                        if (stageOf(x) == 2) { if (x < 0) deleteNow() else flagNow() }
                        settle()
                    },
                    onDragCancel = { settle() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        raw += dx
                        val x = shownOf(raw)
                        val next = stageOf(x)
                        if (next != stage) {
                            if (next == 2) Haptics.heavy(view) else if (next > stage) Haptics.tick(view, false) else Haptics.tick(view, false)
                            stage = next
                        }
                        scope.launch(com.opensolr.mail.ui.Guard) { shown.snapTo(x) }
                    },
                )
            }
        ),
    ) {
        val x = shown.value
        if (x != 0f) {
            val toLeft = x < 0
            val armed = stageOf(x) == 2
            val fill = if (toLeft) p.accentFill else Color(0xFFF5C518)
            Box(
                Modifier.matchParentSize().background(if (armed) fill else fill.copy(alpha = 0.35f)).padding(horizontal = 24.dp),
                contentAlignment = if (toLeft) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(
                    painterResource(if (toLeft) R.drawable.ic_delete else R.drawable.ic_flag), null,
                    tint = if (toLeft) (if (armed) p.paper else p.ink) else Color(0xFF1A1A1A),
                    modifier = Modifier.size(if (armed) 28.dp else 22.dp),
                )
            }
        }
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToInt(), 0) }) { content() }
    }
    }
}
