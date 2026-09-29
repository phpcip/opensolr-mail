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
import com.opensolr.mail.net.Http
import com.opensolr.mail.net.Online
import com.opensolr.mail.net.SignInRequiredException
import com.opensolr.mail.net.UpdateCheck
import com.opensolr.mail.push.MailPush
import com.opensolr.mail.search.MailSearch
import com.opensolr.mail.sync.Notifier
import com.opensolr.mail.sync.Work
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        /** Pauses before each round of the sync on opening the app; the rounds after the first follow a failed one. */
        private val AUTO_SYNC_RETRY_MS = listOf(0L, 3_000L, 10_000L, 30_000L)
        private const val AUTO_NETWORK_WAIT_MS = 30_000L
        private const val MANUAL_NETWORK_WAIT_MS = 10_000L
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
                // On opening the app the network may still be waking: no read, and no error, until it is up.
                if (minAgeMs > 0 && !Online.await(ctx, AUTO_NETWORK_WAIT_MS)) return@launch
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

    /** While set, the whole screen is covered and nothing can be touched: an action that must finish before anything else. */
    var blocking by mutableStateOf<String?>(null)
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
        toast(R.string.idx_started_toast)
        viewModelScope.launch(Dispatchers.IO + Guard) { Work.indexNow(ctx) }
    }

    /** The index counts read again now, with a word either way. */
    fun refreshIndexStatus() {
        viewModelScope.launch(Guard) {
            val ok = withContext(Dispatchers.IO) { com.opensolr.mail.index.MailIndexer(ctx).refreshStatus() }
            toast(if (ok) R.string.idx_refreshed_toast else R.string.err_generic)
        }
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
                // Forced: the pass stopped a moment ago may still show as running, and must not stop this one from starting.
                if (restart) Work.indexNow(ctx, force = true)
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

    /** Re-use [name] from another phone, or with null make this phone's own index; then indexing goes on. */
    fun chooseIndex(name: String?) {
        prefs.chosenIndex = name ?: MailIndex(ctx).newName
        MailIndex(ctx).forget()
        com.opensolr.mail.index.MailIndexer.clearChoices()
        Work.index(ctx)
    }

    fun signOutOpensolr() {
        viewModelScope.launch(Guard) {
            Work.pauseIndex(ctx)
            runCatching { com.opensolr.mail.net.OpensolrApi(prefs).pushUnregister(null) }
            // The relay addresses are gone: every account subscribes again at the next sign-in instead of trusting a dead one.
            store.all().forEach { a -> store.update(a.key) { it.copy(pushExpires = 0, pushVerified = false) } }
            com.opensolr.mail.index.MailIndexer.exclusive {
                prefs.clearSession()
                MailIndex(ctx).forget()
            }
            signedIn = false
            limits = null
        }
    }

    fun removeAccount(a: MailAccount) {
        viewModelScope.launch(Guard) {
            runCatching { MailPush.remove(ctx, a) }
            // A pass in progress would write the account's mail back after it is deleted.
            Work.pauseIndex(ctx)
            com.opensolr.mail.index.MailIndexer.exclusive {
                if (prefs.signedIn) runCatching {
                    val c = MailIndex(ctx).ensure()
                    SolrClient(c).deleteQuery("account_s:" + com.opensolr.mail.index.MailIndexer.indexKey(a))
                }
                runCatching { FastmailAuth.revoke(ctx, a.key) }
                runCatching { CalendarSync.removeAccount(ctx, a.username) }
                withContext(Dispatchers.IO) { db.forgetAccount(a.key) }
                store.remove(a.key)
            }
            if (prefs.signedIn) Work.index(ctx)
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

    /** A sync is under way (the pull spinner shows only for [busy]); an empty list says loading meanwhile. */
    var syncing by mutableStateOf(false)

    /**
     * [manual] is the pull on the list: spinner and error shown. The sync on opening the app waits for the network,
     * shows nothing, and tries again by itself when a round fails.
     */
    fun refresh(manual: Boolean = false) {
        if (refreshJob?.isActive == true) {
            if (manual) busy = true
            return
        }
        refreshJob = viewModelScope.launch(Guard) {
            if (manual) busy = true
            syncing = true
            try {
                val rounds = if (manual) listOf(0L) else AUTO_SYNC_RETRY_MS
                for ((i, wait) in rounds.withIndex()) {
                    if (wait > 0) delay(wait)
                    val online = Online.await(ctx, if (manual) MANUAL_NETWORK_WAIT_MS else AUTO_NETWORK_WAIT_MS)
                    if (!online && !manual) continue
                    val error = syncAccounts() ?: break
                    if (manual) message = error
                    if (manual || i == rounds.lastIndex) break
                    Http.dropIdleConnections()
                }
            } finally {
                busy = false
                syncing = false
            }
        }
    }

    /** Syncs every account at once, so a slow one holds no other back; the first failure's text, or null when all went through. */
    private suspend fun syncAccounts(): String? = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            store.all().map { a ->
                async {
                    try {
                        Result.success(sync.sync(a).arrived)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure<kotlin.collections.List<Message>>(e)
                    }
                }
            }.awaitAll()
        }
        val arrived = ArrayList<Message>()
        val signIn = HashSet<String>()
        var error: String? = null
        results.forEach { r ->
            r.onSuccess { arrived += it }
            r.onFailure { e ->
                if (e is AccountSignInException) signIn += e.accountKey
                else if (error == null) error = e.message ?: ctx.getString(R.string.err_generic)
            }
        }
        if (signIn.isNotEmpty()) needsLogin = needsLogin + signIn
        arrived.forEach { db.markNotified(it.acc, it.id) }
        runCatching { Notifier.cancelGone(ctx) }
        if (prefs.signedIn) Work.indexNow(ctx)
        error
    }

    /** Conversations of [view]; [flagged] true takes only the flagged ones (pinned on top), false leaves them out. */
    suspend fun threads(view: View, limit: Int, flagged: Boolean? = null): kotlin.collections.List<ThreadRow> = withContext(Dispatchers.IO) {
        val accounts = store.all().map { it.key }.toSet()
        // Every account keeps its own line: the same mail in two accounts is two messages (Sent in one, Inbox in the other).
        db.threads(view, accounts, limit, 0, flagged)
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
        db.withoutCopies(db.thread(acc, threadId))
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
        blocking = ctx.getString(R.string.whole_working)
        viewModelScope.launch(Guard) {
            try {
            holdThreads(rows)
            rows.groupBy { it.acc }.forEach { (acc, rs) ->
                val ids = rs.flatMap { threadIds(it) }
                withContext(Dispatchers.IO) { db.hide(acc, rs.map { it.threadId }, forever = false) }
                io { actions.reportJunk(acc, ids) }
            }
            toast(R.string.reported_junk)
            } finally { blocking = null }
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
    /** What the answer may name and a tap opens: the attachments and subjects of the mails it was given. */
    var aiRefs by mutableStateOf<kotlin.collections.List<com.opensolr.mail.search.MailSearch.AnswerRef>>(emptyList())
        private set
    private var aiJob: Job? = null

    fun askAi(question: String, top: kotlin.collections.List<com.opensolr.mail.search.AiPrompt.Doc>, highlights: Map<String, Map<String, kotlin.collections.List<String>>>) {
        aiJob?.cancel()
        aiQuestion = question
        aiText = ""
        aiRefs = emptyList()
        aiRunning = true
        aiJob = viewModelScope.launch(Guard) {
            val me = coroutineContext[Job]
            try {
                // The answer shows as it is written, word by word.
                val raw = StringBuilder()
                search.answer(question, top, highlights, onRefs = { if (aiJob === me) aiRefs = it }) { chunk ->
                    if (aiJob !== me) return@answer
                    raw.append(chunk)
                    aiText = raw.toString()
                }
                if (aiJob === me && raw.isBlank()) aiText = ctx.getString(R.string.ai_no_answer)
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
        aiRefs = emptyList()
        aiRunning = false
    }

    /** A name in the answer tapped: an attachment opens (downloaded first when needed), a subject opens its conversation. */
    fun openAnswerRef(context: Context, ref: com.opensolr.mail.search.MailSearch.AnswerRef) {
        if (!ref.attachment) { go(Screen.Thread(ref.acc, ref.threadId)); return }
        val account = store.get(ref.acc) ?: return
        viewModelScope.launch(Guard) {
            // The file's blob comes with the mail's body: the mail is read from Fastmail when this phone does not hold it yet.
            val a = withContext(Dispatchers.IO) {
                fun find() = db.message(ref.acc, ref.emailId)?.attachments?.firstOrNull { it.name.equals(ref.label, ignoreCase = true) }
                find() ?: runCatching {
                    if (db.message(ref.acc, ref.emailId) == null) db.upsertMessages(sync.getHeaders(com.opensolr.mail.jmap.Jmap(ctx, account), listOf(ref.emailId)))
                    sync.fetchBody(account, ref.emailId)
                    find()
                }.getOrNull()
            }
            if (a == null) { toast(R.string.err_generic); return@launch }
            openAttachment(context, account, a)
        }
    }

    /** Opens an attachment: a copy already in Downloads at once, otherwise downloaded there first, then opened. */
    suspend fun openAttachment(context: Context, account: MailAccount, a: com.opensolr.mail.data.Attachment) {
        val dl = com.opensolr.mail.ui.AttachmentDownloads
        val there = dl.already(context, account, a)
        if (there != null) {
            if (!dl.open(context, there, dl.typeOf(a))) toast(R.string.att_no_app_saved, dl.fileName(a))
            return
        }
        toast(R.string.att_downloading, dl.fileName(a))
        when (val r = dl.download(context, account, a)) {
            is com.opensolr.mail.ui.AttachmentDownloads.Result.Done -> {
                if (dl.open(context, r.uri, r.type)) message = null
                else toast(R.string.att_no_app_saved, dl.fileName(a))
            }
            is com.opensolr.mail.ui.AttachmentDownloads.Result.Failed -> message = r.reason
        }
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

    /**
     * What a delete takes: the whole conversation, every copy in every folder, so it goes to Trash at once.
     * In Trash and Junk only what is there, which is then deleted for good.
     */
    /** Trash or junk delete of many conversations: their message ids per account, hidden once per account, not one by one. */
    suspend fun deleteMany(rows: kotlin.collections.List<ThreadRow>, viewOf: (ThreadRow) -> View?) {
        holdThreads(rows)
        val perAcc = HashMap<String, ArrayList<String>>()
        val hideKeep = HashMap<String, ArrayList<String>>()
        val hideForever = HashMap<String, ArrayList<String>>()
        rows.forEach { row ->
            val view = viewOf(row)
            val bin = when (view) {
                is View.Unified -> view.role == com.opensolr.mail.data.Role.TRASH || view.role == com.opensolr.mail.data.Role.JUNK
                is View.Box -> withContext(Dispatchers.IO) { db.mailboxes(row.acc).firstOrNull { it.id == view.mailboxId }?.role } in setOf("trash", "junk")
                else -> false
            }
            perAcc.getOrPut(row.acc) { ArrayList() } += threadIds(row, if (bin) view else null)
            (if (bin) hideForever else hideKeep).getOrPut(row.acc) { ArrayList() } += row.threadId
        }
        withContext(Dispatchers.IO) {
            hideKeep.forEach { (acc, t) -> db.hide(acc, t, forever = false) }
            hideForever.forEach { (acc, t) -> db.hide(acc, t, forever = true) }
        }
        perAcc.forEach { (acc, ids) -> actions.delete(acc, ids) }
    }

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

    /**
     * Trash or junk on the whole result set of a search: one request to the server with the select as it ran,
     * the server moves every message at Fastmail and in the index; this phone then only moves what it holds.
     */
    /** A folder as the reader picks it for every mailbox at once: by role where it has one, else by name. */
    data class FolderPick(val role: String?, val name: String) {
        fun matches(m: com.opensolr.mail.data.Mailbox) = if (role != null) m.role == role else m.role == null && m.name.equals(name, ignoreCase = true)
    }

    /** The folders the reader can move a whole result set to: every role and every custom name any mailbox has. */
    fun folderPicks(): kotlin.collections.List<FolderPick> {
        val all = db.mailboxes()
        val out = LinkedHashMap<String, FolderPick>()
        val order = listOf("inbox", "archive", "trash", "junk", "sent", "drafts")
        all.filter { it.role != null && it.role != "drafts" }.sortedBy { order.indexOf(it.role).let { i -> if (i < 0) 99 else i } }.forEach { m -> out.putIfAbsent("r:" + m.role, FolderPick(m.role, m.name)) }
        all.filter { it.role == null }.sortedBy { it.name.lowercase() }.forEach { m -> out.putIfAbsent("n:" + m.name.lowercase(), FolderPick(null, m.name)) }
        return out.values.toList()
    }

    suspend fun mailBulk(target: FolderPick, params: kotlin.collections.List<Pair<String, String>>, expected: Long): Int {
        val action = if (target.role == "junk") "junk" else "move"
        val accounts = withContext(Dispatchers.IO) {
            store.all().mapNotNull { a ->
                val box = db.mailboxes(a.key).firstOrNull { target.matches(it) } ?: return@mapNotNull null
                val token = runCatching { FastmailAuth.accessToken(ctx, a.key) }.getOrNull() ?: return@mapNotNull null
                // An account signed in before the JMAP account id was kept: asked from the session now, used for this request only.
                // The session says the account id and how many objects one Email/set may carry (maxObjectsInSet).
                val session = runCatching { FastmailAuth.fetchSession(token) }.getOrNull()
                val jmapId = a.jmapAccountId.ifBlank { session?.optJSONObject("primaryAccounts")?.optString("urn:ietf:params:jmap:mail").orEmpty() }
                if (jmapId.isBlank()) return@mapNotNull null
                val maxSet = session?.optJSONObject("capabilities")?.optJSONObject("urn:ietf:params:jmap:core")?.optInt("maxObjectsInSet", 0)?.takeIf { it > 0 } ?: 500
                com.opensolr.mail.net.OpensolrApi.BulkAccount(com.opensolr.mail.index.MailIndexer.indexKey(a), jmapId, a.apiUrl, token, box.id, box.name, box.role.orEmpty(), maxSet)
            }
        }
        com.opensolr.mail.util.Diag.init(ctx)
        com.opensolr.mail.util.Diag.log("MailBulk", action + " on " + expected + ": " + store.all().size + " accounts on phone, " + accounts.size + " ready: " +
            accounts.joinToString(" ") { "key=" + it.key + " jmap=" + it.jmapAccountId.isNotBlank() + " api=" + it.apiUrl + " to=" + it.toId.isNotBlank() })
        if (accounts.isEmpty()) throw com.opensolr.mail.net.ServiceException(ctx.getString(R.string.whole_no_accounts))
        val moved = com.opensolr.mail.net.OpensolrApi(prefs).mailBulk(prefs.indexName, action, params, expected, accounts)
        withContext(Dispatchers.IO) {
            moved.forEach { m ->
                val a = store.all().firstOrNull { com.opensolr.mail.index.MailIndexer.indexKey(it) == m.key } ?: return@forEach
                val box = db.mailboxes(a.key).firstOrNull { target.matches(it) } ?: return@forEach
                val held = db.heldIds(a.key, m.emails)
                if (held.isNotEmpty()) db.moveLocal(a.key, held, box.id)
            }
        }
        runCatching { com.opensolr.mail.sync.Notifier.cancelGone(ctx) }
        return moved.sumOf { it.emails.size }
    }

    /** Conversations of [rows] this phone does not hold yet, fetched in a few requests per account rather than one each. */
    suspend fun holdThreads(rows: kotlin.collections.List<ThreadRow>) = withContext(Dispatchers.IO) {
        rows.groupBy { it.acc }.forEach { (acc, rs) ->
            val a = store.get(acc) ?: return@forEach
            val missing = rs.map { it.threadId }.filter { it.isNotEmpty() }.distinct().filter { db.thread(acc, it).isEmpty() }
            if (missing.isNotEmpty()) runCatching { sync.fetchThreads(a, missing) }
        }
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

    /** Once a day on start, from the GitHub releases; a copy installed by a store is updated by that store. */
    fun checkForUpdate() {
        if (com.opensolr.mail.net.SelfUpdate.fromStore(ctx)) return
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

    /** Leaving the app with an undo bar still up: the swipe's action goes through, it is not lost with the screen. */
    override fun onCleared() {
        undo?.let { u -> undo = null; kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch(Guard) { u.commit() } }
        super.onCleared()
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
