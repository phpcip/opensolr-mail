package com.opensolr.mail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.mail.R
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.opensolr.mail.data.View
import com.opensolr.mail.search.MailSearch
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.Avatar
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.Haptics
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.Markdown
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.Zone
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtDate
import com.opensolr.mail.ui.highlighted
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.itemMotion
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private val Corner = RoundedCornerShape(2.dp)

/** A search as it was left: parameters, results and the pages loaded after the first. */
private data class SearchSnapshot(
    val query: String,
    val filters: MailSearch.Filters,
    val groupBy: MailSearch.GroupBy,
    val ai: Boolean,
    val fresh: Boolean,
    val result: MailSearch.Result,
    val extraHits: List<MailSearch.Hit>,
    val extraGroups: List<MailSearch.Group>,
)

/** Search and browse every account at once, the way Opensolr Photos and search.opensolr.com do it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppViewModel, sheet: String?) {
    val p = LocalPalette.current
    if (!vm.signedIn) {
        Column(Modifier.fillMaxSize()) {
            com.opensolr.mail.ui.TopBar(stringResource(R.string.search_hint), onBack = { vm.back() })
            Column(Modifier.padding(20.dp)) { com.opensolr.mail.ui.NeedsOpensolr(vm) }
        }
        return
    }
    LaunchedEffect(Unit) { vm.refreshLimits(minAgeMs = 5 * 60_000) }
    val limits = vm.limits
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val accounts by vm.store.accounts.collectAsState()
    val snap = remember { vm.searchSnapshot as? SearchSnapshot }
    var query by rememberSaveable { mutableStateOf(snap?.query ?: vm.searchQuery) }
    var filters by remember { mutableStateOf(snap?.filters ?: vm.searchFilters) }
    var groupBy by remember { mutableStateOf(snap?.groupBy ?: runCatching { MailSearch.GroupBy.valueOf(vm.prefs.groupBy) }.getOrDefault(MailSearch.GroupBy.NONE)) }
    var ai by remember { mutableStateOf(vm.prefs.aiSearch) }
    var fresh by remember { mutableStateOf(vm.prefs.freshSearch) }
    var result by remember { mutableStateOf(snap?.result) }
    var extraHits by remember { mutableStateOf(snap?.extraHits ?: emptyList()) }
    var extraGroups by remember { mutableStateOf(snap?.extraGroups ?: emptyList()) }
    var skipFirst by remember { mutableStateOf(snap != null && snap.ai == ai && snap.fresh == fresh) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(sheet == "filters") }
    var showGroup by remember { mutableStateOf(sheet == "group") }
    val zonesOpen = vm.keySet("filter_zones")
    vm.keySet("search_folds")
    val focus = remember { FocusRequester() }
    val listState = com.opensolr.mail.ui.rememberListMemory("search", result != null, { vm.positions["search"] ?: (0 to 0) }, { i, o -> vm.positions["search"] = i to o })

    fun run() {
        vm.searchFilters = filters
        scope.launch {
            loading = true
            error = null
            try {
                result = vm.search.search(query, filters, groupBy)
                extraHits = emptyList()
                extraGroups = emptyList()
                // A new result always opens at its top: the list would otherwise stay anchored on
                // whatever row it showed before (the empty list's last row) and land mid-way down.
                vm.positions["search"] = 0 to 0
                runCatching { listState.scrollToItem(0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: vm.text(R.string.err_generic)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (sheet == null && snap == null) focus.requestFocus() }
    // Another question stops the answer to the previous one at once.
    LaunchedEffect(query) { if (vm.aiQuestion != null && vm.aiQuestion != query) vm.stopAi() }
    LaunchedEffect(query, filters) {
        vm.searchQuery = query
        vm.searchFilters = filters
    }
    LaunchedEffect(query, filters, groupBy, ai, fresh) {
        if (skipFirst) { skipFirst = false; return@LaunchedEffect }
        if (query.isNotEmpty()) delay(350)
        run()
    }
    LaunchedEffect(result, extraHits, extraGroups) {
        val res = result ?: return@LaunchedEffect
        vm.searchSnapshot = SearchSnapshot(query, filters, groupBy, ai, fresh, res, extraHits, extraGroups)
    }

    val r = result
    r?.let { knownNames = knownNames + it.names }
    // Later pages can bring more matches from a conversation already listed: it stays one line.
    // One line per conversation, and one per mail: the same message held by two accounts (an alias, a copy sent to both) shows once.
    val shownHits = remember(r, extraHits) {
        ((r?.hits.orEmpty()) + extraHits).distinctBy { it.acc + ":" + it.threadId.ifEmpty { it.emailId } }
            .distinctBy { it.messageId.ifEmpty { it.acc + ":" + it.emailId } }
    }
    val shownGroups = (r?.groups.orEmpty()) + extraGroups
    // The next page comes once the reader is a quarter of the way down what is loaded, well before the end,
    // so neither scrolling nor the fast scroller ever reaches a bottom that is not the real one.
    val atEnd by remember { derivedStateOf {
        val total = listState.layoutInfo.totalItemsCount
        // 20 rows ahead of the end, or the bottom itself (the fast scroller held there): the next page comes.
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        total > 0 && (last >= total - 20 || (!listState.canScrollForward && listState.canScrollBackward))
    } }
    LaunchedEffect(atEnd, shownHits.size, shownGroups.size) {
        val res = result ?: return@LaunchedEffect
        if (!atEnd || loadingMore || loading) return@LaunchedEffect
        val grouped = groupBy != MailSearch.GroupBy.NONE
        val more = if (grouped) shownGroups.size < res.total && shownGroups.size % 20 == 0 && shownGroups.isNotEmpty() else shownHits.size < res.total
        if (!more) return@LaunchedEffect
        loadingMore = true
        runCatching { vm.search.search(query, filters, groupBy, start = if (grouped) shownGroups.size else shownHits.size) }.onSuccess { next ->
            if (grouped) extraGroups = extraGroups + next.groups else extraHits = extraHits + next.hits
        }
        loadingMore = false
    }

    // The same controls as the mail lists: swipe to delete or flag, long tap to select, the same bar of actions.
    // Flag and read changes show at once; the index catches up at the next indexing.
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val flagNow = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val seenNow = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // Conversations deleted or archived from here stay out of the results until the index catches up.
    val gone = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    var confirmDelete by remember { mutableStateOf<com.opensolr.mail.data.ThreadRow?>(null) }
    // A result in Trash or Junk acts on what lies there, like those folders do: a delete there is for good.
    val binOf = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    fun viewOf(row: com.opensolr.mail.data.ThreadRow): View? = when (binOf[row.acc + ":" + row.threadId]) {
        "trash" -> View.Unified(com.opensolr.mail.data.Role.TRASH)
        "junk" -> View.Unified(com.opensolr.mail.data.Role.JUNK)
        else -> null
    }
    fun keyOf(h: MailSearch.Hit) = h.acc + ":" + h.threadId
    // Flag and read state as this phone holds it, which leads the index: one query per account for the results shown.
    val dbVersion by vm.db.version.collectAsState()
    var held by remember { mutableStateOf<Map<String, Pair<Boolean, Boolean>>>(emptyMap()) }
    LaunchedEffect(shownHits, shownGroups, dbVersion) {
        val hits = shownHits + shownGroups.flatMap { it.hits }
        held = withContext(Dispatchers.IO) {
            hits.filter { it.threadId.isNotEmpty() }.groupBy { it.acc }.flatMap { (acc, hs) ->
                vm.db.threadStates(acc, hs.map { it.threadId }).map { (t, st) -> "$acc:$t" to st }
            }.toMap()
        }
    }
    fun flaggedOf(h: MailSearch.Hit) = flagNow[keyOf(h)] ?: held[keyOf(h)]?.first ?: h.flagged
    fun seenOf(h: MailSearch.Hit) = seenNow[keyOf(h)] ?: held[keyOf(h)]?.let { !it.second } ?: h.seen
    fun rowOf(h: MailSearch.Hit) = com.opensolr.mail.data.ThreadRow(
        acc = h.acc, threadId = h.threadId, latestId = h.emailId, subject = h.subject, senders = h.from, preview = h.snippet,
        received = h.received, count = h.threadCount, unread = !seenOf(h), flagged = flaggedOf(h),
        hasAttachment = h.hasAttachment, fromName = h.from, fromEmail = h.fromEmail,
    )
    val allHits = (shownHits + shownGroups.flatMap { it.hits }).filter { it.threadId.isNotEmpty() }.distinctBy { keyOf(it) }
    val selectedRows = allHits.filter { keyOf(it) in selectedKeys }.map { rowOf(it) }
    androidx.activity.compose.BackHandler(enabled = selectedKeys.isNotEmpty()) { selectedKeys = emptySet() }

    @Composable
    fun ActionHit(h: MailSearch.Hit) {
        val k = keyOf(h)
        if (h.bin.isNotEmpty()) binOf[k] = h.bin
        val shown = h.copy(flagged = flaggedOf(h), seen = seenOf(h))
        val row = rowOf(h)
        SwipeRow(
            key = row,
            onDelete = { if (vm.prefs.confirmSwipeDelete) confirmDelete = row else { gone[k] = true; vm.deleteWithUndo(row, viewOf(row)) { gone.remove(k) } } },
            onFlag = { val was = row.flagged; flagNow[k] = !was; vm.toggleFlagWithUndo(row) { flagNow[k] = was } },
            enabled = selectedKeys.isEmpty() && h.threadId.isNotEmpty(),
        ) {
            HitRow(
                vm, shown, accounts.size > 1, accounts.firstOrNull { it.key == h.acc }?.color, selected = k in selectedKeys,
                onClick = {
                    if (selectedKeys.isNotEmpty()) selectedKeys = if (k in selectedKeys) selectedKeys - k else selectedKeys + k
                    else if (h.threadId.isNotEmpty()) { seenNow[k] = true; vm.go(Screen.Thread(h.acc, h.threadId)) }
                },
                onLongClick = { if (h.threadId.isNotEmpty()) selectedKeys = selectedKeys + k },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selectedKeys.isNotEmpty()) com.opensolr.mail.ui.TopBar(stringResource(R.string.selected_n, selectedKeys.size), onBack = { selectedKeys = emptySet() }) {}
        else Row(Modifier.fillMaxWidth().background(p.band).padding(horizontal = 6.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(R.drawable.ic_back, { vm.back() })
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = p.muted)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink), cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { Haptics.tick(view, true); run() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) IconBtn(R.drawable.ic_close, { query = "" })
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().background(p.toolFill).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconAction(R.drawable.ic_group, active = groupBy != MailSearch.GroupBy.NONE) { showGroup = true }
                DropdownMenu(expanded = showGroup, onDismissRequest = { showGroup = false }, containerColor = p.paper) {
                    Text(stringResource(R.string.group_by), style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    MailSearch.GroupBy.entries.forEach { how ->
                        DropdownMenuItem(
                            text = { Text(stringResource(groupLabel(how)), style = MaterialTheme.typography.bodyLarge, fontWeight = if (how == groupBy) FontWeight.Bold else FontWeight.Medium, color = if (how == groupBy) p.accent else p.ink) },
                            leadingIcon = { if (how == groupBy) Icon(Icons.Filled.Check, null, tint = p.accent, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
                            onClick = { Haptics.tick(view, false); showGroup = false; groupBy = how; vm.prefs.groupBy = how.name },
                        )
                    }
                }
            }
            IconAction(R.drawable.ic_filters, active = filters.count > 0, badge = filters.count) { showFilters = true }
            if (groupBy != MailSearch.GroupBy.NONE) {
                val anyOpen = vm.anyUnfolded("search_folds", shownGroups.map { groupBy.name + ":" + it.value })
                IconAction(if (anyOpen) R.drawable.ic_collapse_all else R.drawable.ic_expand_all, active = false) { vm.foldAll("search_folds", anyOpen) }
            }
            Spacer(Modifier.width(8.dp))
            Toggle(stringResource(R.string.ai), ai) { ai = it; vm.prefs.aiSearch = it }
            Spacer(Modifier.width(6.dp))
            Toggle(stringResource(R.string.fresh), fresh) { fresh = it; vm.prefs.freshSearch = it }
            Spacer(Modifier.weight(1f))
            r?.let { Text(String.format(Locale.US, "%,d", it.total), style = MaterialTheme.typography.labelSmall, color = p.muted) }
        }
        ActivePills(filters, onChange = { filters = it })
        // What the plan stops right now: a closed index has no search, a spent AI allowance leaves words only.
        if (limits?.closed == true) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { com.opensolr.mail.ui.Notice(stringResource(R.string.search_closed_text), title = stringResource(R.string.search_closed_title)) }
        } else if (ai && limits != null && !limits.aiUsable) {
            Text(
                stringResource(if (limits.vectorAllowed) R.string.search_ai_off_quota else R.string.search_ai_off_plan),
                style = MaterialTheme.typography.bodySmall, color = p.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.hairline) else Hairline()

        val groupLabels = shownGroups.map { groupValueLabel(groupBy, it.value, accounts) }
        val searchFolds = vm.keySet("search_folds")
        val hasAnswerCard = r != null && query.isNotBlank() && shownHits.isNotEmpty() && limits?.aiUsable != false
        val hasEmpty = r != null && !loading && shownHits.isEmpty()
        val scrollIndex = remember(shownHits, shownGroups, groupBy, groupLabels, searchFolds, hasAnswerCard, error != null, hasEmpty, loadingMore) {
            val dayLabel = SimpleDateFormat("EEE, MM/dd/yyyy", Locale.getDefault())
            com.opensolr.mail.ui.ScrollIndex().apply {
                if (hasAnswerCard) row()
                if (error != null) row()
                if (hasEmpty) row()
                if (groupBy == MailSearch.GroupBy.NONE) {
                    // Ranked results carry no headings; the day of each hit is its title.
                    shownHits.forEach { h -> row(dayLabel.format(java.util.Date(h.received))) }
                } else {
                    shownGroups.forEachIndexed { i, g ->
                        head(groupLabels[i])
                        if (!vm.isFolded("search_folds", groupBy.name + ":" + g.value)) {
                            rows(g.hits.size)
                            if (g.total > g.hits.size) row()
                        }
                    }
                }
                if (loadingMore) row()
                row()
            }
        }
        com.opensolr.mail.ui.RefreshBox(refreshing = loading, onRefresh = { vm.stopAi(); run() }, modifier = Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (hasAnswerCard) item(key = "ai") {
                // The answer shown belongs to the question typed now; another question offers a new one.
                val mine = vm.aiQuestion == query
                AnswerCard(if (mine) vm.aiText else null, mine && vm.aiRunning) {
                    // The first rows of the list on screen, in their order, go to the answer: nothing else.
                    val res = r ?: return@AnswerCard
                    val rows = if (groupBy != MailSearch.GroupBy.NONE) shownGroups.flatMap { it.hits } else shownHits
                    val byId = res.docs.associateBy { it.id }
                    val top = rows.distinctBy { it.acc + ":" + it.threadId.ifEmpty { it.emailId } }
                        .mapNotNull { byId[it.docId] }.take(com.opensolr.mail.search.AiPrompt.TOP_N)
                    vm.askAi(query, top, res.highlights)
                }
            }
            error?.let { e -> item(key = "err") { Text(e, style = MaterialTheme.typography.bodyMedium, color = p.accent, modifier = Modifier.padding(16.dp)) } }
            if (r != null && !loading && shownHits.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.empty_view), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(24.dp))
            }
            if (groupBy == MailSearch.GroupBy.NONE) {
                items(shownHits.filter { vm.hiddenThreads[keyOf(it)] != true && gone[keyOf(it)] != true }, key = { "h:" + it.acc + ":" + it.emailId }) { h -> Box(itemMotion()) { ActionHit(h) } }
            } else {
                shownGroups.forEach { g ->
                    val key = groupBy.name + ":" + g.value
                    val folded = vm.isFolded("search_folds", key)
                    item(key = "g:$key") {
                        Box(itemMotion()) { GroupHeader(groupValueLabel(groupBy, g.value, accounts), g.total, !folded) { vm.toggleFold("search_folds", key) } }
                    }
                    if (!folded) {
                        items(g.hits.filter { vm.hiddenThreads[keyOf(it)] != true && gone[keyOf(it)] != true }.distinctBy { it.messageId.ifEmpty { it.acc + ":" + it.emailId } }, key = { "gh:$key:" + it.acc + ":" + it.emailId }) { h -> Box(itemMotion()) { ActionHit(h) } }
                        if (g.total > g.hits.size) item(key = "more:$key") {
                            Text(
                                stringResource(R.string.show_all_n, String.format(Locale.US, "%,d", g.total)),
                                style = MaterialTheme.typography.labelLarge, color = p.accent,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    Haptics.tick(view, false)
                                    val field = groupBy.field ?: return@clickable
                                    filters = if (field == "month_s" || field == "day_s") filters.copy(dates = rangeOf(field, g.value))
                                    else filters.toggled(groupFacet(field), g.value)
                                    groupBy = MailSearch.GroupBy.NONE
                                    vm.prefs.groupBy = groupBy.name
                                }.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            if (loadingMore) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.hairline) }
            item(key = "end") { Spacer(Modifier.height(bottomInset())) }
        }
        FastScroller(listState, scrollIndex)
        }
        if (selectedRows.isNotEmpty()) {
            val anyUnread = selectedRows.any { it.unread }
            val anyUnflagged = selectedRows.any { !it.flagged }
            fun each(block: suspend (String, List<String>) -> Unit) {
                val rows = selectedRows
                vm.viewModelScopeLaunch { rows.groupBy { it.acc }.forEach { (acc, rs) -> block(acc, rs.flatMap { vm.threadIds(it) }) } }
            }
            SelectionBar(
                onRead = { selectedRows.forEach { seenNow[it.acc + ":" + it.threadId] = anyUnread }; each { acc, ids -> vm.setSeen(acc, ids, anyUnread) }; selectedKeys = emptySet() },
                readIcon = if (anyUnread) R.drawable.ic_check else R.drawable.ic_unread,
                readLabel = if (anyUnread) R.string.tool_read else R.string.tool_unread,
                onFlag = { selectedRows.forEach { flagNow[it.acc + ":" + it.threadId] = anyUnflagged }; each { acc, ids -> vm.setFlagged(acc, ids, anyUnflagged) }; selectedKeys = emptySet() },
                onArchive = { selectedRows.forEach { gone[it.acc + ":" + it.threadId] = true }; each { acc, ids -> vm.archive(acc, ids) }; selectedKeys = emptySet() },
                onDelete = {
                    val rows = selectedRows
                    rows.forEach { gone[it.acc + ":" + it.threadId] = true }
                    vm.viewModelScopeLaunch { rows.groupBy { it.acc }.forEach { (acc, rs) -> vm.delete(acc, rs.flatMap { vm.deleteIds(it, viewOf(it)) }) } }
                    selectedKeys = emptySet()
                },
                onForward = { vm.forwardSelected(selectedRows); selectedKeys = emptySet() },
                onJunk = if (selectedRows.none { binOf[it.acc + ":" + it.threadId] == "junk" }) ({
                    selectedRows.forEach { gone[it.acc + ":" + it.threadId] = true }
                    vm.reportJunkRows(selectedRows); selectedKeys = emptySet()
                }) else null,
                // Results that all lie in Trash, or all in Junk, can go back to the Inbox, as from those folders.
                restoreLabel = selectedRows.map { binOf[it.acc + ":" + it.threadId].orEmpty() }.distinct().singleOrNull()?.let {
                    when (it) { "junk" -> R.string.not_junk; "trash" -> R.string.move_to_inbox; else -> null }
                },
                onRestore = {
                    val rows = selectedRows
                    val notJunk = rows.all { binOf[it.acc + ":" + it.threadId] == "junk" }
                    vm.viewModelScopeLaunch { rows.groupBy { it.acc }.forEach { (acc, rs) -> vm.restoreToInbox(acc, rs.flatMap { vm.threadIds(it, viewOf(it)) }, notJunk) } }
                    rows.forEach { binOf.remove(it.acc + ":" + it.threadId) }
                    selectedKeys = emptySet()
                },
            )
        }
    }
    confirmDelete?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(if (viewOf(row) != null) R.string.confirm_delete_forever else R.string.confirm_delete_trash)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    Haptics.heavy(view); confirmDelete = null
                    val k = row.acc + ":" + row.threadId
                    gone[k] = true
                    vm.deleteWithUndo(row, viewOf(row)) { gone.remove(k) }
                }) {
                    Text(stringResource(R.string.delete), color = p.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel), color = p.ink) } },
            containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.muted,
        )
    }

    if (showFilters) {
        FilterSheet(
            facets = r?.facets.orEmpty(), current = filters, total = r?.total ?: 0, accounts = accounts.map { it.username },
            open = zonesOpen, onToggle = { vm.toggleKey("filter_zones", it) }, onAll = { vm.setKeySet("filter_zones", it) }, onChange = { filters = it }, onDismiss = { showFilters = false },
        )
    }
}

private fun groupLabel(g: MailSearch.GroupBy): Int = when (g) {
    MailSearch.GroupBy.NONE -> R.string.group_none
    MailSearch.GroupBy.DATE -> R.string.group_month
    MailSearch.GroupBy.DAY -> R.string.group_day
    MailSearch.GroupBy.SENDER -> R.string.group_sender
    MailSearch.GroupBy.COMPANY -> R.string.group_company
    MailSearch.GroupBy.ACCOUNT -> R.string.group_account
}

/** The facet a group's "show all" filters on. */
private fun groupFacet(field: String): String = when (field) {
    "from_domain_s" -> "domains_ss"
    else -> field
}

private fun rangeOf(field: String, value: String): MailSearch.DateRange? = runCatching {
    val utc = TimeZone.getTimeZone("UTC")
    if (field == "day_s") {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.parse(value)!!.time
        MailSearch.DateRange(d, d)
    } else {
        val from = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = utc }.parse(value)!!.time
        val c = java.util.Calendar.getInstance(utc).apply { timeInMillis = from; add(java.util.Calendar.MONTH, 1); add(java.util.Calendar.DAY_OF_MONTH, -1) }
        MailSearch.DateRange(from, c.timeInMillis)
    }
}.getOrNull()

@Composable
private fun groupValueLabel(g: MailSearch.GroupBy, value: String, accounts: List<com.opensolr.mail.data.MailAccount>): String = when (g) {
    MailSearch.GroupBy.DATE -> runCatching {
        val d = SimpleDateFormat("yyyy-MM", Locale.US).parse(value)!!
        SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(d).replaceFirstChar { it.uppercase() }
    }.getOrDefault(value)
    MailSearch.GroupBy.DAY -> runCatching {
        SimpleDateFormat("MM/dd/yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value)!!)
    }.getOrDefault(value)
    MailSearch.GroupBy.SENDER -> knownNames[value]?.let { "$it ($value)" } ?: value.ifBlank { stringResource(R.string.no_sender) }
    else -> value.ifBlank { stringResource(R.string.no_sender) }
}

@Composable
private fun IconAction(icon: Int, active: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Box(
        Modifier.padding(end = 6.dp).size(36.dp).background(p.paper, Corner).border(1.dp, if (active) p.accent else p.hairline, Corner)
            .clickable { Haptics.tick(view, false); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (active) p.accent else p.ink, modifier = Modifier.size(22.dp))
        if (badge > 0) Text(
            "$badge", style = MaterialTheme.typography.labelSmall, color = p.onAccentFill,
            modifier = Modifier.align(Alignment.TopEnd).background(p.accentFill, Corner).padding(horizontal = 3.dp),
        )
    }
}

/** A switch-like pill for the AI and Fresh toggles. */
@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Box(
        Modifier.height(36.dp).background(if (on) p.accentFill else p.paper, Corner).border(1.dp, if (on) p.accentFill else p.hairline, Corner)
            .clickable { Haptics.toggle(view, !on); onChange(!on) }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) p.onAccentFill else p.ink) }
}

@Composable
private fun ActivePills(filters: MailSearch.Filters, onChange: (MailSearch.Filters) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val pills = buildList {
        filters.facets.forEach { (field, values) -> values.forEach { v -> add(facetValueLabel(field, v) to filters.toggled(field, v)) } }
        filters.dates?.let {
            val day = SimpleDateFormat("MM/dd/yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            add(day.format(it.from) + " – " + day.format(it.to) to filters.copy(dates = null))
        }
        if (filters.unread) add(stringResource(R.string.unread) to filters.copy(unread = false))
        if (filters.flagged) add(stringResource(R.string.flagged) to filters.copy(flagged = false))
        if (filters.answered) add(stringResource(R.string.f_answered) to filters.copy(answered = false))
        if (filters.attachments) add(stringResource(R.string.with_attachments) to filters.copy(attachments = false))
        if (filters.attachmentText) add(stringResource(R.string.f_attachment_text) to filters.copy(attachmentText = false))
        if (filters.includeTrash) add(stringResource(R.string.include_trash) to filters.copy(includeTrash = false))
    }
    if (pills.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(p.toolFill).horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pills.forEach { (label, without) ->
            Row(
                Modifier.background(p.accentFill, Corner).clickable { Haptics.tick(view, false); onChange(without) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = p.onAccentFill, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(painterResource(R.drawable.ic_close), null, tint = p.onAccentFill, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** The names behind addresses, from the last search; used to label the From, To and sender groups. */
private var knownNames: Map<String, String> = emptyMap()

@Composable
private fun facetValueLabel(field: String, value: String): String = when (field) {
    "from_s", "to_ss" -> knownNames[value]?.let { "$it ($value)" } ?: value
    "weekday_i" -> value.toIntOrNull()?.let { DateFormatSymbols.getInstance().weekdays.getOrNull(it) } ?: value
    "attachment_ext_ss" -> "." + value
    else -> value
}

private fun facetTitle(field: String): Int = when (field) {
    "from_s" -> R.string.f_from
    "to_ss" -> R.string.f_to
    "domains_ss" -> R.string.f_companies
    "account_email_s" -> R.string.f_accounts
    "mailbox_name_ss" -> R.string.f_folders
    "year_i" -> R.string.f_year
    "attachment_ext_ss" -> R.string.f_attachment_types
    "weekday_i" -> R.string.f_weekday
    else -> R.string.filters
}

@Composable
private fun GroupHeader(label: String, total: Long, open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(p.accent.copy(alpha = 0.12f), Corner)
            .clickable { Haptics.tick(view, false); onToggle() }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowDownCompat else Icons.Filled.KeyboardArrowRightCompat,
            null, tint = p.accent, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(String.format(Locale.US, "%,d", total), style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HitRow(vm: AppViewModel, h: MailSearch.Hit, multi: Boolean, color: Int?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().background(if (selected) p.chip else if (h.flagged) p.flagFill else if (!h.seen) com.opensolr.mail.ui.unreadFill() else p.paper)) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { Haptics.tick(view, true); onLongClick() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(72.dp).background(if (selected) p.accent else if (multi && color != null) Color(color) else Color.Transparent))
        Spacer(Modifier.width(9.dp))
        if (selected) Box(Modifier.size(40.dp).background(p.accentFill, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_check), null, tint = p.onAccentFill, modifier = Modifier.size(22.dp))
        } else Avatar(if (h.from == h.fromEmail) "" else h.from, h.fromEmail)
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!h.seen) { com.opensolr.mail.ui.UnreadDot(); Spacer(Modifier.width(6.dp)) }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.from, style = MaterialTheme.typography.bodyMedium, fontWeight = if (h.seen) FontWeight.Medium else FontWeight.Bold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    com.opensolr.mail.ui.CountBadge(h.threadCount)
                }
                Spacer(Modifier.width(6.dp))
                Text(fmtDate(h.received), style = MaterialTheme.typography.bodySmall, color = if (h.seen) p.muted else p.accent, fontWeight = if (h.seen) null else FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.subject.ifBlank { stringResource(R.string.no_subject) }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (h.seen) null else FontWeight.Bold, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (h.hasAttachment) com.opensolr.mail.ui.AttachBadge()
                if (h.flagged) com.opensolr.mail.ui.FlagBadge()
            }
            Text(highlighted(h.snippet), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    com.opensolr.mail.ui.StackEdges(h.threadCount)
    Hairline()
    }
}

@Composable
private fun AnswerCard(answer: String?, answering: Boolean, onAsk: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().padding(10.dp).background(p.band, Corner).border(1.dp, p.hairline, Corner).padding(12.dp)) {
        if (answer == null && !answering) {
            Row(Modifier.clickable { Haptics.tick(view, true); onAsk() }, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_ask), null, tint = p.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ask_answer), style = MaterialTheme.typography.labelLarge, color = p.accent)
            }
        } else {
            Text(stringResource(R.string.answer).uppercase(), style = MaterialTheme.typography.labelMedium, color = p.muted)
            Spacer(Modifier.height(6.dp))
            if (answer.isNullOrBlank()) Text(stringResource(R.string.thinking), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            else androidx.compose.foundation.text.selection.SelectionContainer { Markdown(answer) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    facets: Map<String, List<MailSearch.Facet>>,
    current: MailSearch.Filters,
    total: Long,
    accounts: List<String>,
    open: Set<String>,
    onToggle: (String) -> Unit,
    onAll: (Set<String>) -> Unit,
    onChange: (MailSearch.Filters) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.paper, shape = Corner) {
        val sheetScroll = rememberScrollState()
        val sheetMarks = remember { com.opensolr.mail.ui.ScrollMarks() }
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.runtime.CompositionLocalProvider(com.opensolr.mail.ui.LocalScrollMarks provides sheetMarks) {
            Column(Modifier.fillMaxWidth().verticalScroll(sheetScroll).padding(horizontal = 20.dp).navigationBarsPadding()) {
            val allZones = listOf("dates", "is") + MailSearch.Filters.FACETS
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filters), style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.weight(1f))
                val anyOpen = allZones.any { it in open }
                IconAction(if (anyOpen) R.drawable.ic_collapse_all else R.drawable.ic_expand_all, active = false) { onAll(if (anyOpen) emptySet() else allZones.toSet()) }
            }
            Spacer(Modifier.height(8.dp))
            SheetActions(total, onClear = { onChange(MailSearch.Filters()) }, onDone = onDismiss)
            Spacer(Modifier.height(8.dp))

            Zone(stringResource(R.string.f_dates), if (current.dates != null) 1 else 0, "dates" in open, { onToggle("dates") }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SheetChip(current.dates?.let { fmtDate(it.from).take(10) + " – " + fmtDate(it.to).take(10) } ?: stringResource(R.string.f_choose_dates), current.dates != null) { picking = true }
                    if (current.dates != null) SheetChip(stringResource(R.string.f_any_date), false) { onChange(current.copy(dates = null)) }
                }
            }

            val switchesOn = listOf(current.unread, current.flagged, current.answered, current.attachments, current.attachmentText, current.includeTrash).count { it }
            Zone(stringResource(R.string.f_message_is), switchesOn, "is" in open, { onToggle("is") }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SheetChip(stringResource(R.string.unread), current.unread) { onChange(current.copy(unread = !current.unread)) }
                    SheetChip(stringResource(R.string.flagged), current.flagged) { onChange(current.copy(flagged = !current.flagged)) }
                    SheetChip(stringResource(R.string.f_answered), current.answered) { onChange(current.copy(answered = !current.answered)) }
                    SheetChip(stringResource(R.string.with_attachments), current.attachments) { onChange(current.copy(attachments = !current.attachments)) }
                    SheetChip(stringResource(R.string.f_attachment_text), current.attachmentText) { onChange(current.copy(attachmentText = !current.attachmentText)) }
                    SheetChip(stringResource(R.string.include_trash), current.includeTrash) { onChange(current.copy(includeTrash = !current.includeTrash)) }
                }
            }

            MailSearch.Filters.FACETS.forEach { field ->
                if (field == "account_email_s" && accounts.size < 2) return@forEach
                val values = facets[field].orEmpty()
                val chosen = current.values(field)
                if (values.isEmpty() && chosen.isEmpty()) return@forEach
                Zone(stringResource(facetTitle(field)), chosen.size, field in open, { onToggle(field) }) {
                    FacetValues(field, values, chosen) { v -> Haptics.tick(view, v !in chosen); onChange(current.toggled(field, v)) }
                }
            }
            Hairline()
            Spacer(Modifier.height(12.dp))
            SheetActions(total, onClear = { onChange(MailSearch.Filters()) }, onDone = onDismiss)
            Spacer(Modifier.height(24.dp))
        }
            }
            FastScroller(sheetScroll, sheetMarks)
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = current.dates?.from, initialSelectedEndDateMillis = current.dates?.to)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            colors = DatePickerDefaults.colors(containerColor = p.paper),
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val from = state.selectedStartDateMillis
                    val to = state.selectedEndDateMillis ?: from
                    if (from != null && to != null) onChange(current.copy(dates = MailSearch.DateRange(minOf(from, to), maxOf(from, to)), facets = current.facets - "year_i"))
                    picking = false
                }) { Text(stringResource(R.string.apply), color = p.accent) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel), color = p.muted) } },
        ) {
            DateRangePicker(
                state = state,
                title = { Text(stringResource(R.string.f_dates), style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.padding(start = 20.dp, top = 16.dp)) },
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = p.paper, selectedDayContainerColor = p.accentFill, selectedDayContentColor = p.onAccentFill,
                    dayInSelectionRangeContainerColor = p.chip, todayDateBorderColor = p.accent,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetValues(field: String, values: List<MailSearch.Facet>, chosen: Set<String>, onToggle: (String) -> Unit) {
    val p = LocalPalette.current
    var all by remember(field) { mutableStateOf(false) }
    var filter by remember(field) { mutableStateOf("") }
    val list = values.ifEmpty { chosen.map { MailSearch.Facet(it, 0) } }
    val matching = if (filter.isBlank()) list else list.filter { it.value.contains(filter, ignoreCase = true) }
    val shown = if (all || matching.size <= PREVIEW) matching else matching.take(PREVIEW) + matching.drop(PREVIEW).filter { it.value in chosen }
    if (list.size > PREVIEW) {
        Box(Modifier.fillMaxWidth().border(1.dp, p.hairline, Corner).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (filter.isEmpty()) Text(stringResource(R.string.f_find), style = MaterialTheme.typography.bodyMedium, color = p.muted)
            BasicTextField(filter, { filter = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { f ->
            SheetChip(if (f.count > 0) "${facetValueLabel(field, f.value)} (${String.format(Locale.US, "%,d", f.count)})" else facetValueLabel(field, f.value), f.value in chosen) { onToggle(f.value) }
        }
        if (matching.size > PREVIEW) SheetChip(if (all) stringResource(R.string.show_fewer) else stringResource(R.string.show_all_n, String.format(Locale.US, "%,d", matching.size)), false) { all = !all }
    }
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Box(
        Modifier.background(if (selected) p.accentFill else p.buttonFill, Corner).border(1.dp, if (selected) p.accentFill else p.hairline, Corner)
            .clickable { Haptics.tap(view); onClick() }.padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) p.onAccentFill else p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SheetActions(total: Long, onClear: () -> Unit, onDone: () -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Box(Modifier.height(36.dp).border(1.dp, p.hairline, Corner).clickable { Haptics.tick(view, false); onClear() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.clear_all), style = MaterialTheme.typography.labelMedium, color = p.ink)
        }
        Box(Modifier.height(36.dp).background(p.accentFill, Corner).clickable { Haptics.tick(view, true); onDone() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.done_count, String.format(Locale.US, "%,d", total)), style = MaterialTheme.typography.labelMedium, color = p.onAccentFill)
        }
    }
}

private const val PREVIEW = 12

private val Icons.Filled.KeyboardArrowDownCompat get() = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown
private val Icons.Filled.KeyboardArrowRightCompat get() = androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight
