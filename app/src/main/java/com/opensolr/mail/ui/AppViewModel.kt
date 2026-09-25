package com.opensolr.mail.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opensolr.mail.AppText
import com.opensolr.mail.R
import com.opensolr.mail.auth.FastmailAuth
import com.opensolr.mail.auth.OpensolrAuth
import com.opensolr.mail.dav.CalendarSync
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Message
import com.opensolr.mail.data.Role
import com.opensolr.mail.data.ThreadRow
import com.opensolr.mail.data.View
import com.opensolr.mail.index.MailIndex
import com.opensolr.mail.index.MailIndexer
import com.opensolr.mail.index.SolrClient
import com.opensolr.mail.jmap.MailActions
import com.opensolr.mail.jmap.MailSync
import com.opensolr.mail.net.AccountSignInException
import com.opensolr.mail.net.SignInRequiredException
import com.opensolr.mail.net.UpdateCheck
import com.opensolr.mail.push.MailPush
import com.opensolr.mail.search.MailSearch
import com.opensolr.mail.sync.Notifier
import com.opensolr.mail.sync.Work
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object Mailboxes : Screen()
    data class List(val view: View) : Screen()
    data class Thread(val acc: String, val threadId: String) : Screen()
    data class Compose(val init: ComposeInit) : Screen()
    data class Search(val sheet: String? = null) : Screen()
    data class Notes(val acc: String) : Screen()
    data class NoteEdit(val acc: String, val noteId: String?) : Screen()
    object Settings : Screen()
    object Contacts : Screen()
}

/** What a compose screen opens with. */
data class ComposeInit(
    val acc: String? = null,
    val identityId: String? = null,
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val answeredId: String? = null,
    val draftId: String? = null,
    /** Files already in the app to attach, like the messages of a bulk forward. */
    val files: kotlin.collections.List<com.opensolr.mail.jmap.MailActions.OutFile> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val ALL_FOLDED = "\u0000all"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }


    private val ctx: Context get() = getApplication()
    val prefs = AppPrefs(app)
    val store = AccountStore.get(app)
    val db = MailDb.get(app)
    private val actions = MailActions(app)
    private val sync = MailSync(app)
    val search = MailSearch(app)
    val fastmailSearch = com.opensolr.mail.search.FastmailSearch(app)

    val stack = mutableStateListOf<Screen>(Screen.List(View.Unified(Role.INBOX)))
    val screen: Screen get() = stack.last()

    var signedIn by mutableStateOf(prefs.signedIn)

    /** Search on Fastmail (classic, words only) instead of the Opensolr Index; chosen in Settings. */
    var useFastmailSearch by mutableStateOf(prefs.fastmailSearch)
        private set

    fun chooseFastmailSearch(on: Boolean) {
        useFastmailSearch = on
        prefs.fastmailSearch = on
        // A list left by the other search is not shown again.
        searchSnapshot = null
    }

    /** The Opensolr plan and its usage; null without an Opensolr account or before the first read. */
    var limits by mutableStateOf(prefs.limits)
    var limitsError by mutableStateOf<String?>(null)
    var limitsLoading by mutableStateOf(false)

    /** Reads the plan and its usage again; skipped when read less than [minAgeMs] ago. */
    fun refreshLimits(minAgeMs: Long = 0) {
        if (!prefs.signedIn || limitsLoading) return
        val name = prefs.indexName
        if (name.isBlank()) return
        if (minAgeMs > 0 && (limits?.refreshedAt ?: 0) > System.currentTimeMillis() - minAgeMs) return
        limitsLoading = true
        viewModelScope.launch(Guard) {
            try {
                val l = com.opensolr.mail.net.OpensolrApi(prefs).accountSummary(name, limits)
                prefs.limits = l
                prefs.vectorAllowed = l.vectorAllowed
                limits = l
                limitsError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInRequired(e)
                limitsError = e.message ?: ctx.getString(R.string.err_generic)
            } finally {
                limitsLoading = false
            }
        }
    }
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var needsLogin by mutableStateOf<Set<String>>(emptySet())

    var update by mutableStateOf<UpdateCheck.Update?>(null)
    var updateProgress by mutableStateOf<Int?>(null)
    var updateChecking by mutableStateOf(false)
    var updateResult by mutableStateOf<String?>(null)
    var updateInstallError by mutableStateOf<String?>(null)

    // Named sets of keys (folded groups, open zones), remembered for good; reading one subscribes the screen to changes.
    private val keyCache = HashMap<String, Set<String>>()
    private var keysVersion by androidx.compose.runtime.mutableIntStateOf(0)

    fun keySet(name: String): Set<String> {
        keysVersion
        return keyCache.getOrPut(name) { prefs.keys(name) }
    }

    fun setKeySet(name: String, keys: Set<String>) {
        keyCache[name] = keys
        prefs.saveKeys(name, keys)
        keysVersion++
    }

    fun toggleKey(name: String, key: String) {
        val now = keySet(name)
        setKeySet(name, if (key in now) now - key else now + key)
    }

    /**
     * Folded groups of a grouped list. "Collapse all" / "Expand all" set the rule for every group,
     * including the ones not loaded yet; a tap on one group is an exception to that rule.
     */
    fun isFolded(name: String, key: String): Boolean {
        val s = keySet(name)
        return (ALL_FOLDED in s) != (key in s)
    }

    fun toggleFold(name: String, key: String) = toggleKey(name, key)

    fun foldAll(name: String, folded: Boolean) = setKeySet(name, if (folded) setOf(ALL_FOLDED) else emptySet())

    fun anyUnfolded(name: String, keys: Collection<String>): Boolean = keys.any { !isFolded(name, it) }

    /** Settings zones open; every zone starts folded. */
    val zonesOpen: Set<String> get() = keySet("settings_zones")

    fun toggleZone(key: String) = toggleKey("settings_zones", key)

    /** Which messages of each conversation were open, for the session. */
    val threadOpen = HashMap<String, Set<String>>()

    /** The text of the last search, kept for the whole session. */
    var searchQuery = ""

    fun indexNow() {
        viewModelScope.launch(Dispatchers.IO + Guard) { Work.indexNow(ctx) }
    }

    fun stopIndex() {
        Work.stopIndex(ctx)
        toast(R.string.idx_stopped_toast)
    }

    /** Repair: asks once more for the vectors that are missing, then indexes. */
    fun repairVectors() {
        viewModelScope.launch(Dispatchers.IO + Guard) {
            try {
                val n = com.opensolr.mail.index.MailIndexer(ctx).queueMissingVectors()
                Work.indexNow(ctx)
                withContext(Dispatchers.Main) { toast(R.string.idx_repair_started, java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = e.message ?: ctx.getString(R.string.err_generic) }
            }
        }
    }

    /** Reindex (keep the data), Reindex clean (empty first, then index) or Reset (empty and stay stopped). */
    fun startOver(wipe: Boolean, restart: Boolean) {
        Work.stopIndex(ctx)
        viewModelScope.launch(Dispatchers.IO + Guard) {
            try {
                com.opensolr.mail.index.MailIndexer(ctx).startOver(wipe, restart)
                if (restart) Work.indexNow(ctx)
                withContext(Dispatchers.Main) { toast(if (!restart) R.string.idx_reset_done else R.string.idx_reindex_started) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = e.message ?: ctx.getString(R.string.err_generic) }
            }
        }
    }

    /** Positions of screens that are not worth a disk write each (one per conversation), kept while the app is open. */
    val positions = HashMap<String, Pair<Int, Int>>()

    /** The last search with its results and paging, so coming back to it shows it unchanged. */
    var searchSnapshot: Any? = null

    /** The search entry whose sheet was already opened: coming back to it does not open the sheet again. */
    var searchSheetShown: Screen? = null

    /** The search filters, kept while the app is open. */
    var searchFilters = com.opensolr.mail.search.MailSearch.Filters()

    fun go(s: Screen) { stack.add(s) }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun home(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun toast(id: Int, vararg args: Any) { message = ctx.getString(id, *args) }

    // ---------------- sign-in ----------------

    fun startOpensolrSignIn(context: Context) = OpensolrAuth.start(context, prefs)

    fun startFastmailSignIn(context: Context) = FastmailAuth.start(context, prefs)

    fun onAuthCallback(uri: Uri) {
        viewModelScope.launch(Guard) {
            try {
                when {
                    OpensolrAuth.isCallback(uri) -> {
                        OpensolrAuth.finish(ctx, uri)
                        signedIn = true
                        onReady()
                    }
                    FastmailAuth.isCallback(uri) -> {
                        val a = FastmailAuth.finish(ctx, uri)
                        needsLogin = needsLogin - a.key
                        if (a.calendarSync) runCatching { CalendarSync.ensureAccount(ctx, a.username) }
                        refresh()
                        Work.schedule(ctx)
                        runCatching { MailPush.ensure(ctx) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: ctx.getString(R.string.err_generic)
            }
        }
    }

    /** Opensolr signed in: register the phone, start the index. */
    private fun onReady() {
        Work.schedule(ctx)
        viewModelScope.launch(Guard) { runCatching { MailPush.ensure(ctx) } }
        Work.index(ctx)
    }

    fun signOutOpensolr() {
        viewModelScope.launch(Guard) {
            runCatching { com.opensolr.mail.net.OpensolrApi(prefs).pushUnregister(null) }
            // The relay addresses are gone: every account subscribes again at the next sign-in instead of trusting a dead one.
            store.all().forEach { a -> store.update(a.key) { it.copy(pushExpires = 0, pushVerified = false) } }
            prefs.clearSession()
            MailIndex(ctx).forget()
            signedIn = false
            limits = null
        }
    }

    fun removeAccount(a: MailAccount) {
        viewModelScope.launch(Guard) {
            runCatching { MailPush.remove(ctx, a) }
            if (prefs.signedIn) runCatching {
                val c = MailIndex(ctx).ensure()
                SolrClient(c).deleteQuery("account_s:" + com.opensolr.mail.index.MailIndexer.indexKey(a))
            }
            runCatching { FastmailAuth.revoke(ctx, a.key) }
            runCatching { CalendarSync.removeAccount(ctx, a.username) }
            withContext(Dispatchers.IO) { db.forgetAccount(a.key) }
            store.remove(a.key)
            home(Screen.List(View.Unified(Role.INBOX)))
        }
    }

    fun setCalendarSync(a: MailAccount, on: Boolean) {
        store.update(a.key) { it.copy(calendarSync = on) }
        if (on) {
            runCatching { CalendarSync.ensureAccount(ctx, a.username) }
            runCatching { CalendarSync.requestSync(a.username) }
        } else runCatching { CalendarSync.removeAccount(ctx, a.username) }
    }

    // ---------------- mail ----------------

    private var refreshJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(Guard) {
            busy = true
            try {
                val arrived = ArrayList<Message>()
                withContext(Dispatchers.IO) { store.all().forEach { a ->
                    try {
                        arrived += sync.sync(a).arrived
                    } catch (e: AccountSignInException) {
                        needsLogin = needsLogin + e.accountKey
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = e.message ?: ctx.getString(R.string.err_generic)
                    }
                }
                arrived.forEach { db.markNotified(it.acc, it.id) }
                runCatching { Notifier.cancelGone(ctx) }
                if (prefs.signedIn) Work.indexNow(ctx) }
            } finally {
                busy = false
            }
        }
    }

    /** Conversations of [view]; [flagged] true takes only the flagged ones (pinned on top), false leaves them out. */
    suspend fun threads(view: View, limit: Int, flagged: Boolean? = null): kotlin.collections.List<ThreadRow> = withContext(Dispatchers.IO) {
        val accounts = store.all().map { it.key }.toSet()
        // One account's folder cannot hold a message twice; across accounts the same mail (an alias, a copy sent
        // to both) shows once, by its Message-ID. Enough rows are read that the page stays full after that.
        if (view is View.Box || accounts.size < 2) return@withContext db.threads(view, accounts, limit, 0, flagged)
        var want = limit
        var out: kotlin.collections.List<ThreadRow> = emptyList()
        repeat(4) {
            val rows = db.threads(view, accounts, want, 0, flagged)
            val ids = rows.groupBy { it.acc }.flatMap { (acc, rs) -> db.messageIds(acc, rs.map { it.latestId }).map { (id, mid) -> "$acc:$id" to mid } }.toMap()
            out = rows.distinctBy { r -> ids["${r.acc}:${r.latestId}"]?.takeIf { it.isNotBlank() } ?: "${r.acc}:${r.threadId}" }
            if (out.size >= limit || rows.size < want) return@withContext out.take(limit)
            want += limit - out.size
        }
        out.take(limit)
    }

    suspend fun loadOlder(view: View): Int = withContext(Dispatchers.IO) {
        try {
            sync.loadOlder(view)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun openThread(acc: String, threadId: String): kotlin.collections.List<Message> = withContext(Dispatchers.IO) {
        val account = store.get(acc) ?: return@withContext emptyList()
        // The whole conversation, every folder: the phone holds only the mail it synced (Inbox pages, recent
        // changes), so the server is asked which messages the thread has and only the missing ones are fetched.
        runCatching { sync.fetchThread(account, threadId) }
        db.thread(acc, threadId)
    }

    /** Bodies of every message in [list] that has none yet, in one request per account. */
    suspend fun bodies(list: kotlin.collections.List<Message>): kotlin.collections.List<Message> = withContext(Dispatchers.IO) {
        list.filter { it.bodyHtml == null }.groupBy { it.acc }.forEach { (acc, ms) ->
            store.get(acc)?.let { a -> runCatching { sync.fetchBodies(a, ms.map { it.id }) } }
        }
        list.groupBy { it.acc }.flatMap { (acc, ms) -> db.messages(acc, ms.map { it.id }) }
    }

    suspend fun body(m: Message): Message = withContext(Dispatchers.IO) {
        if (m.bodyHtml != null) return@withContext m
        val account = store.get(m.acc) ?: return@withContext m
        runCatching { sync.fetchBody(account, m.id) }
        db.message(m.acc, m.id) ?: m
    }

    fun viewModelScopeLaunch(block: suspend () -> Unit) {
        viewModelScope.launch(Guard) { block() }
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + Guard) { block() }
    }

    fun markThreadRead(acc: String, ids: kotlin.collections.List<String>) {
        if (ids.isEmpty()) return
        io { actions.setSeen(acc, ids, true) }
        ids.forEach { Notifier.cancel(ctx, acc, it) }
    }

    fun setSeen(acc: String, ids: kotlin.collections.List<String>, seen: Boolean) = io { actions.setSeen(acc, ids, seen) }
    fun setFlagged(acc: String, ids: kotlin.collections.List<String>, flagged: Boolean) = io { actions.setFlagged(acc, ids, flagged) }
    fun delete(acc: String, ids: kotlin.collections.List<String>) = io { actions.delete(acc, ids) }
    fun archive(acc: String, ids: kotlin.collections.List<String>) = io { actions.archive(acc, ids) }
    fun reportJunk(acc: String, ids: kotlin.collections.List<String>) = io { actions.reportJunk(acc, ids) }

    /** Whole conversations reported as spam: they leave the lists by moving to Junk, where they show. */
    fun reportJunkRows(rows: kotlin.collections.List<ThreadRow>) {
        if (rows.isEmpty()) return
        viewModelScope.launch(Guard) {
            rows.groupBy { it.acc }.forEach { (acc, rs) ->
                val ids = rs.flatMap { threadIds(it) }
                withContext(Dispatchers.IO) { db.hide(acc, rs.map { it.threadId }, forever = false) }
                io { actions.reportJunk(acc, ids) }
            }
            toast(R.string.reported_junk)
        }
    }
    /**
     * Forwards the selected conversations together: the newest message of each is attached as an .eml,
     * read with one Email/get per account and downloaded once, then compose opens with them.
     */
    fun forwardSelected(rows: kotlin.collections.List<ThreadRow>) {
        if (rows.isEmpty()) return
        toast(R.string.preparing_forward)
        viewModelScope.launch(Guard) {
            val files = ArrayList<com.opensolr.mail.jmap.MailActions.OutFile>()
            val subjects = ArrayList<String>()
            withContext(Dispatchers.IO) {
                val dir = java.io.File(ctx.filesDir, "outbox").apply { mkdirs() }
                rows.take(20).groupBy { it.acc }.forEach { (acc, rs) ->
                    val account = store.get(acc) ?: return@forEach
                    val jmap = com.opensolr.mail.jmap.Jmap(ctx, account)
                    runCatching {
                        val r = jmap.call("Email/get", org.json.JSONObject().put("ids", org.json.JSONArray(rs.map { it.latestId })).put("properties", org.json.JSONArray(listOf("id", "blobId", "subject", "size"))))
                        val list = r.getJSONArray("list")
                        for (i in 0 until list.length()) {
                            val o = list.getJSONObject(i)
                            if (o.optLong("size") > 25L * 1024 * 1024) continue
                            val subject = o.optString("subject").ifBlank { "message" }
                            val name = subject.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).trim().ifBlank { "message" } + ".eml"
                            val target = java.io.File(dir, System.nanoTime().toString() + "_" + name)
                            jmap.download(o.getString("blobId"), name, "message/rfc822", target)
                            files += com.opensolr.mail.jmap.MailActions.OutFile(target.path, name, "message/rfc822")
                            subjects += subject
                        }
                    }
                }
            }
            if (files.isEmpty()) { toast(R.string.err_generic); return@launch }
            val subject = if (subjects.size == 1) "Fwd: " + subjects[0] else "Fwd: " + ctx.getString(R.string.n_messages, subjects.size)
            go(Screen.Compose(ComposeInit(acc = rows.first().acc, subject = subject, files = files)))
        }
    }

    fun restoreToInbox(acc: String, ids: kotlin.collections.List<String>, notJunk: Boolean) = io {
        db.unhide(acc, db.threadsOf(acc, ids))
        actions.restoreToInbox(acc, ids, notJunk)
    }

    /**
     * The AI answer, kept here and not in the screen: it runs to the end whatever the screen does
     * (a refresh, opening a result, turning the phone) and is only replaced by a new question.
     */
    var aiQuestion by mutableStateOf<String?>(null)
        private set
    var aiText by mutableStateOf<String?>(null)
        private set
    var aiRunning by mutableStateOf(false)
        private set
    private var aiJob: Job? = null

    fun askAi(question: String, top: kotlin.collections.List<com.opensolr.mail.search.AiPrompt.Doc>, highlights: Map<String, Map<String, kotlin.collections.List<String>>>) {
        aiJob?.cancel()
        aiQuestion = question
        aiText = ""
        aiRunning = true
        aiJob = viewModelScope.launch(Guard) {
            val me = coroutineContext[Job]
            try {
                // NO_ANSWER from the model is shown as the app's own sentence, in the reader's language;
                // while it is still arriving, nothing of it is shown.
                val raw = StringBuilder()
                val none = ctx.getString(R.string.ai_no_answer)
                search.answer(question, top, highlights) { chunk ->
                    if (aiJob !== me) return@answer
                    raw.append(chunk)
                    val t = raw.trim()
                    aiText = when {
                        t.startsWith(com.opensolr.mail.search.AiPrompt.NO_ANSWER) -> none
                        com.opensolr.mail.search.AiPrompt.NO_ANSWER.startsWith(t) -> ""
                        else -> raw.toString()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.opensolr.mail.net.QuotaExceededException) {
                // The allowance ran out: say so, and fetch the plan again so AI greys out at once.
                aiText = ctx.getString(R.string.search_ai_off_quota)
                refreshLimits()
            } catch (e: com.opensolr.mail.net.VectorNotAllowedException) {
                aiText = ctx.getString(R.string.search_ai_off_plan)
                refreshLimits()
            } catch (e: Exception) {
                aiText = e.message ?: ctx.getString(R.string.err_generic)
            } finally {
                if (aiJob === me) aiRunning = false
            }
        }
    }

    /** Stops the answer being written and clears it: a refresh or another question never keeps the old stream going. */
    fun stopAi() {
        aiJob?.cancel()
        aiJob = null
        aiQuestion = null
        aiText = null
        aiRunning = false
    }

    /** The last swipe, still undoable: a delete waits here unsent until the bar goes, a flag is undone by flagging back. */
    data class Undo(val id: Long, val text: String, val undo: () -> Unit, val commit: suspend () -> Unit)

    var undo by mutableStateOf<Undo?>(null)
        private set

    /** Conversations deleted by a swipe whose undo bar is still up: out of the list, not yet deleted. */
    val hiddenThreads = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    private fun offerUndo(text: String, onUndo: () -> Unit, commit: suspend () -> Unit) {
        undo?.let { previous -> viewModelScope.launch(Guard) { previous.commit() } }
        undo = Undo(System.nanoTime(), text, onUndo, commit)
    }

    fun undoLast() {
        val u = undo ?: return
        undo = null
        u.undo()
    }

    /** The undo bar timed out: the action goes through. */
    fun commitUndo(id: Long) {
        val u = undo?.takeIf { it.id == id } ?: return
        undo = null
        viewModelScope.launch(Guard) { u.commit() }
    }

    /** A confirmed swipe delete: hidden at once, sent only when the undo bar is gone. */
    fun deleteWithUndo(row: ThreadRow, view: View?, onUndone: () -> Unit = {}) {
        val key = row.acc + ":" + row.threadId
        hiddenThreads[key] = true
        // Out of search from the swipe on, also while Undo is still offered; Undo lets it back.
        io { db.hide(row.acc, listOf(row.threadId), forever = false) }
        offerUndo(ctx.getString(R.string.deleted_one), onUndo = { hiddenThreads.remove(key); io { db.unhide(row.acc, listOf(row.threadId)) }; onUndone() }) {
            val ids = deleteIds(row, view)
            withContext(Dispatchers.IO) { actions.delete(row.acc, ids) }
            delay(1500)
            hiddenThreads.remove(key)
        }
    }

    /** A swipe flag or unflag, applied at once and undone by putting the flags back as they were. */
    fun toggleFlagWithUndo(row: ThreadRow, onUndone: () -> Unit = {}) {
        viewModelScope.launch(Guard) {
            val ids = threadIds(row)
            if (row.flagged) {
                val before = withContext(Dispatchers.IO) { db.messages(row.acc, ids).filter { it.flagged }.map { it.id } }
                io { actions.setFlagged(row.acc, ids, false) }
                if (prefs.undoSwipeFlag) offerUndo(ctx.getString(R.string.unflagged_one), onUndo = { io { actions.setFlagged(row.acc, before, true) }; onUndone() }) {}
            } else {
                val last = ids.takeLast(1)
                io { actions.setFlagged(row.acc, last, true) }
                if (prefs.undoSwipeFlag) offerUndo(ctx.getString(R.string.flagged_one), onUndo = { io { actions.setFlagged(row.acc, last, false) }; onUndone() }) {}
            }
        }
    }
    fun move(acc: String, ids: kotlin.collections.List<String>, to: String) = io { actions.move(acc, ids, to) }

    /** The real mailboxes behind a view: one per account for a unified role, or the box itself. */
    private fun boxesOf(view: View): kotlin.collections.List<Pair<String, String>> = when (view) {
        is View.Unified -> store.accounts.value.mapNotNull { a -> db.mailboxByRole(a.key, view.role)?.let { a.key to it.id } }
        is View.Box -> listOf(view.acc to view.mailboxId)
        View.Flagged -> emptyList()
    }

    fun readAll(view: View) = io {
        boxesOf(view).forEach { (acc, box) -> actions.readAll(acc, box) }
        message = text(com.opensolr.mail.R.string.marked_all_read)
    }

    fun empty(view: View) = io {
        boxesOf(view).forEach { (acc, box) -> actions.empty(acc, box) }
        message = text(com.opensolr.mail.R.string.emptied)
    }

    fun send(o: MailActions.Outgoing) {
        io { actions.send(o) }
        toast(R.string.sending)
    }

    fun saveDraft(o: MailActions.Outgoing) {
        io { actions.saveDraft(o) }
        toast(R.string.draft_saved)
    }

    /** Messages of a thread held locally, for the thread's bulk actions. */
    /** The messages of a conversation that belong to [view]: a move or delete never drags the Sent copies along. */
    /**
     * What a delete takes: the whole conversation, every copy in every folder, so it goes to Trash at once.
     * In Trash and Junk only what is there, which is then deleted for good.
     */
    suspend fun deleteIds(row: ThreadRow, view: View?): kotlin.collections.List<String> {
        val bin = when (view) {
            is View.Unified -> view.role == com.opensolr.mail.data.Role.TRASH || view.role == com.opensolr.mail.data.Role.JUNK
            is View.Box -> withContext(Dispatchers.IO) { db.mailboxes(row.acc).firstOrNull { it.id == view.mailboxId }?.role } in setOf("trash", "junk")
            else -> false
        }
        val ids = threadIds(row, if (bin) view else null)
        // Out of search at once and for good, whatever the index still says: a Trash or Junk delete is final.
        withContext(Dispatchers.IO) { db.hide(row.acc, listOf(row.threadId), forever = bin) }
        return ids
    }

    suspend fun threadIds(row: ThreadRow, view: View? = null): kotlin.collections.List<String> = withContext(Dispatchers.IO) {
        // A search result can be a conversation this phone does not hold yet: it is fetched first, so the
        // action reaches every message of it instead of none.
        var msgs = db.thread(row.acc, row.threadId)
        if (msgs.isEmpty() && row.threadId.isNotEmpty()) {
            store.get(row.acc)?.let { a -> runCatching { sync.fetchThread(a, row.threadId) } }
            msgs = db.thread(row.acc, row.threadId)
        }
        when (view) {
            null -> msgs
            is View.Box -> msgs.filter { view.mailboxId in it.mailboxIds }
            is View.Unified -> {
                val ids = db.mailboxes(row.acc).filter { it.role == view.role.jmap }.map { it.id }.toSet()
                msgs.filter { m -> m.mailboxIds.any { it in ids } }
            }
            View.Flagged -> msgs.filter { it.flagged }
        }.map { it.id }
    }

    // ---------------- updates ----------------

    /** Once a day on start, from the GitHub releases; a copy installed by Google Play is updated by Play. */
    fun checkForUpdate() {
        if (com.opensolr.mail.net.SelfUpdate.fromPlay(ctx)) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch(Guard) {
            val u = UpdateCheck.check().getOrNull() ?: return@launch
            prefs.lastUpdateCheck = System.currentTimeMillis()
            update = u
        }
    }

    fun checkForUpdateNow() {
        if (updateChecking) return
        updateChecking = true
        updateResult = null
        viewModelScope.launch(Guard) {
            val outcome = UpdateCheck.check()
            prefs.lastUpdateCheck = System.currentTimeMillis()
            val u = outcome.getOrNull()
            updateChecking = false
            when {
                outcome.isFailure -> updateResult = ctx.getString(R.string.vm_update_failed)
                u == null -> updateResult = ctx.getString(R.string.vm_update_latest)
                else -> { update = u; updateResult = ctx.getString(R.string.vm_update_found, u.version) }
            }
        }
    }

    fun installUpdate(context: Context) {
        val newer = update ?: return
        if (updateProgress != null) return
        if (!com.opensolr.mail.net.SelfUpdate.canInstall(context)) {
            updateInstallError = ctx.getString(R.string.acc_update_allow)
            com.opensolr.mail.net.SelfUpdate.openInstallPermission(context)
            return
        }
        updateProgress = 0
        updateInstallError = null
        viewModelScope.launch(Guard) {
            val outcome = com.opensolr.mail.net.SelfUpdate.downloadAndInstall(context.applicationContext, newer.apkUrl) { pct -> updateProgress = pct }
            updateProgress = null
            updateInstallError = when (outcome) {
                is com.opensolr.mail.net.SelfUpdate.Outcome.Started -> null
                is com.opensolr.mail.net.SelfUpdate.Outcome.NeedsPermission -> ctx.getString(R.string.acc_update_allow)
                is com.opensolr.mail.net.SelfUpdate.Outcome.Invalid -> ctx.getString(R.string.acc_update_invalid)
                is com.opensolr.mail.net.SelfUpdate.Outcome.Failed -> ctx.getString(R.string.acc_update_failed)
            }
        }
    }

    val indexStatus get() = MailIndexer.status

    init {
        MailIndexer(app).restore()
        viewModelScope.launch {
            AppPrefs.sessionEnded.collect { if (it > 0 && !prefs.signedIn) { signedIn = false; limits = null } }
        }
        if (prefs.signedIn && !prefs.hasDeviceKey) viewModelScope.launch(Guard) {
            try { com.opensolr.mail.net.OpensolrApi(prefs).upgradeToDeviceKey() } catch (e: CancellationException) { throw e } catch (e: Exception) { signInRequired(e) }
        }
        if (store.all().isNotEmpty()) {
            Work.schedule(app)
            refresh()
            viewModelScope.launch(Guard) { delay(1500); runCatching { MailPush.ensure(ctx) } }
        }
    }

    fun signInRequired(e: Throwable) {
        if (e is SignInRequiredException) {
            prefs.clearSession()
            signedIn = false
            limits = null
        }
    }

    fun text(id: Int): String = AppText.s(id)
}
