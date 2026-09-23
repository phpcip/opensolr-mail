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

    val stack = mutableStateListOf<Screen>(Screen.List(View.Unified(Role.INBOX)))
    val screen: Screen get() = stack.last()

    var signedIn by mutableStateOf(prefs.signedIn)

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
        viewModelScope.launch {
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
        viewModelScope.launch(Dispatchers.IO) { Work.indexNow(ctx) }
    }

    /** Positions of screens that are not worth a disk write each (one per conversation), kept while the app is open. */
    val positions = HashMap<String, Pair<Int, Int>>()

    /** The last search with its results and paging, so coming back to it shows it unchanged. */
    var searchSnapshot: Any? = null

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
        viewModelScope.launch {
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
        viewModelScope.launch { runCatching { MailPush.ensure(ctx) } }
        Work.index(ctx)
    }

    fun signOutOpensolr() {
        viewModelScope.launch {
            runCatching { com.opensolr.mail.net.OpensolrApi(prefs).pushUnregister(null) }
            prefs.clearSession()
            MailIndex(ctx).forget()
            signedIn = false
            limits = null
        }
    }

    fun removeAccount(a: MailAccount) {
        viewModelScope.launch {
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
        refreshJob = viewModelScope.launch {
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
        db.threads(view, store.all().map { it.key }.toSet(), limit, 0, flagged)
    }

    suspend fun loadOlder(view: View): Int = withContext(Dispatchers.IO) {
        try {
            sync.loadOlder(view)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
    }

    suspend fun openThread(acc: String, threadId: String): kotlin.collections.List<Message> = withContext(Dispatchers.IO) {
        val account = store.get(acc) ?: return@withContext emptyList()
        // The change stream keeps local threads complete; the server is asked only for a thread not held here (a search hit from years ago).
        var list = db.thread(acc, threadId)
        if (list.isEmpty()) {
            runCatching { sync.fetchThread(account, threadId) }
            list = db.thread(acc, threadId)
        }
        list
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
        viewModelScope.launch { block() }
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
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
    /**
     * Forwards the selected conversations together: the newest message of each is attached as an .eml,
     * read with one Email/get per account and downloaded once, then compose opens with them.
     */
    fun forwardSelected(rows: kotlin.collections.List<ThreadRow>) {
        if (rows.isEmpty()) return
        toast(R.string.preparing_forward)
        viewModelScope.launch {
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

    fun restoreToInbox(acc: String, ids: kotlin.collections.List<String>, notJunk: Boolean) = io { actions.restoreToInbox(acc, ids, notJunk) }

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

    fun askAi(question: String, filters: com.opensolr.mail.search.MailSearch.Filters) {
        aiJob?.cancel()
        aiQuestion = question
        aiText = ""
        aiRunning = true
        aiJob = viewModelScope.launch {
            try {
                search.answer(question, filters) { chunk -> aiText = (aiText ?: "") + chunk }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aiText = e.message ?: ctx.getString(R.string.err_generic)
            } finally {
                aiRunning = false
            }
        }
    }

    /** The last swipe, still undoable: a delete waits here unsent until the bar goes, a flag is undone by flagging back. */
    data class Undo(val id: Long, val text: String, val undo: () -> Unit, val commit: suspend () -> Unit)

    var undo by mutableStateOf<Undo?>(null)
        private set

    /** Conversations deleted by a swipe whose undo bar is still up: out of the list, not yet deleted. */
    val hiddenThreads = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    private fun offerUndo(text: String, onUndo: () -> Unit, commit: suspend () -> Unit) {
        undo?.let { previous -> viewModelScope.launch { previous.commit() } }
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
        viewModelScope.launch { u.commit() }
    }

    /** A confirmed swipe delete: hidden at once, sent only when the undo bar is gone. */
    fun deleteWithUndo(row: ThreadRow, view: View?) {
        val key = row.acc + ":" + row.threadId
        hiddenThreads[key] = true
        offerUndo(ctx.getString(R.string.deleted_one), onUndo = { hiddenThreads.remove(key) }) {
            val ids = threadIds(row, view)
            withContext(Dispatchers.IO) { actions.delete(row.acc, ids) }
            delay(1500)
            hiddenThreads.remove(key)
        }
    }

    /** A swipe flag or unflag, applied at once and undone by putting the flags back as they were. */
    fun toggleFlagWithUndo(row: ThreadRow) {
        viewModelScope.launch {
            val ids = threadIds(row)
            if (row.flagged) {
                val before = withContext(Dispatchers.IO) { db.messages(row.acc, ids).filter { it.flagged }.map { it.id } }
                io { actions.setFlagged(row.acc, ids, false) }
                if (prefs.undoSwipeFlag) offerUndo(ctx.getString(R.string.unflagged_one), onUndo = { io { actions.setFlagged(row.acc, before, true) } }) {}
            } else {
                val last = ids.takeLast(1)
                io { actions.setFlagged(row.acc, last, true) }
                if (prefs.undoSwipeFlag) offerUndo(ctx.getString(R.string.flagged_one), onUndo = { io { actions.setFlagged(row.acc, last, false) } }) {}
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
    suspend fun threadIds(row: ThreadRow, view: View? = null): kotlin.collections.List<String> = withContext(Dispatchers.IO) {
        val msgs = db.thread(row.acc, row.threadId)
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
        viewModelScope.launch {
            val u = UpdateCheck.check().getOrNull() ?: return@launch
            prefs.lastUpdateCheck = System.currentTimeMillis()
            update = u
        }
    }

    fun checkForUpdateNow() {
        if (updateChecking) return
        updateChecking = true
        updateResult = null
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        if (store.all().isNotEmpty()) {
            Work.schedule(app)
            refresh()
            viewModelScope.launch { delay(1500); runCatching { MailPush.ensure(ctx) } }
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
