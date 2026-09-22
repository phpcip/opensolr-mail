package com.opensolr.mail.jmap

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.Address
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Role
import com.opensolr.mail.jmap.MailSync.Companion.strings
import com.opensolr.mail.sync.Notifier
import com.opensolr.mail.sync.Work
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every change a person makes: applied to the local copy at once, queued, and sent to Fastmail by
 * the ops worker as soon as there is a network. Nothing is lost offline.
 */
class MailActions(private val context: Context) {

    private val db = MailDb.get(context)

    fun setSeen(acc: String, ids: List<String>, seen: Boolean) {
        db.setFlags(acc, ids, seen = seen)
        enqueue(acc, "seen", JSONObject().put("ids", JSONArray(ids)).put("value", seen))
    }

    fun setFlagged(acc: String, ids: List<String>, flagged: Boolean) {
        db.setFlags(acc, ids, flagged = flagged)
        enqueue(acc, "flag", JSONObject().put("ids", JSONArray(ids)).put("value", flagged))
    }

    fun move(acc: String, ids: List<String>, to: String) {
        db.moveLocal(acc, ids, to)
        enqueue(acc, "move", JSONObject().put("ids", JSONArray(ids)).put("to", to))
    }

    /** To the trash; out of the trash (or junk) it is gone for good. */
    fun delete(acc: String, ids: List<String>) {
        val trash = db.mailboxByRole(acc, Role.TRASH)
        val boxes = db.mailboxIdsOf(acc, ids)
        val permanent = ids.filter { id -> trash == null || boxes[id]?.contains(trash.id) == true }
        val toTrash = ids - permanent.toSet()
        if (toTrash.isNotEmpty() && trash != null) move(acc, toTrash, trash.id)
        if (permanent.isNotEmpty()) {
            db.deleteMessages(acc, permanent)
            enqueue(acc, "destroy", JSONObject().put("ids", JSONArray(permanent)))
        }
    }

    /** Out of Trash or Junk, back into the Inbox; [notJunk] also tells Fastmail it was not spam. */
    fun restoreToInbox(acc: String, ids: List<String>, notJunk: Boolean) {
        val inbox = db.mailboxByRole(acc, Role.INBOX) ?: return
        db.moveLocal(acc, ids, inbox.id)
        enqueue(acc, "restore", JSONObject().put("ids", JSONArray(ids)).put("to", inbox.id).put("notjunk", notJunk))
    }

    /** Every message of a mailbox marked read, on Fastmail too, however many there are. */
    fun readAll(acc: String, box: String) {
        db.readAllLocal(acc, box)
        enqueue(acc, "read_box", JSONObject().put("box", box))
    }

    /** A mailbox (Trash, Junk) emptied for good: its messages are destroyed, not moved anywhere. */
    fun empty(acc: String, box: String) {
        db.emptyLocal(acc, box)
        enqueue(acc, "empty_box", JSONObject().put("box", box))
    }

    fun archive(acc: String, ids: List<String>) {
        val archive = db.mailboxByRole(acc, Role.ARCHIVE) ?: return
        move(acc, ids, archive.id)
    }

    /** A message to send, as the compose screen hands it over. Attachment files are already copied into the app. */
    data class Outgoing(
        val acc: String,
        val identityId: String,
        val from: Address,
        val to: List<Address>,
        val cc: List<Address>,
        val bcc: List<Address>,
        val subject: String,
        val text: String,
        val inReplyTo: String,
        val references: String,
        val answeredId: String?,
        val replacesDraftId: String?,
        val attachments: List<OutFile>,
    )

    companion object {
        private val MERGEABLE = setOf("seen", "flag", "move", "destroy")
        private const val PAGE = 500
        private const val MAX_PAGES = 400
    }

    data class OutFile(val path: String, val name: String, val type: String)

    fun send(o: Outgoing) = enqueue(o.acc, "send", toJson(o))

    fun saveDraft(o: Outgoing) = enqueue(o.acc, "draft", toJson(o))

    private fun toJson(o: Outgoing) = JSONObject()
        .put("identity", o.identityId)
        .put("from", o.from.toJson())
        .put("to", JSONArray(o.to.map { it.toJson() }))
        .put("cc", JSONArray(o.cc.map { it.toJson() }))
        .put("bcc", JSONArray(o.bcc.map { it.toJson() }))
        .put("subject", o.subject)
        .put("text", o.text)
        .put("in_reply_to", o.inReplyTo)
        .put("references", o.references)
        .put("answered", o.answeredId ?: "")
        .put("replaces", o.replacesDraftId ?: "")
        .put("files", JSONArray(o.attachments.map { JSONObject().put("path", it.path).put("name", it.name).put("type", it.type) }))

    private fun enqueue(acc: String, kind: String, payload: JSONObject) {
        db.enqueueOp(acc, kind, payload.toString())
        Work.runOps(context)
    }

    /** Runs the queue in order. Returns false when something must be retried later. */
    suspend fun runQueue(): Boolean {
        val store = AccountStore.get(context)
        var ok = true
        val touched = LinkedHashSet<String>()
        // Consecutive changes of the same kind on the same account go out as one Email/set.
        val ops = db.ops()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            val merged = arrayListOf(op)
            if (op.kind in MERGEABLE) {
                val key = mergeKey(op)
                while (i + merged.size < ops.size) {
                    val next = ops[i + merged.size]
                    if (next.acc != op.acc || next.kind != op.kind || mergeKey(next) != key) break
                    merged += next
                }
            }
            i += merged.size
            val account = store.get(op.acc)
            if (account == null) { merged.forEach { db.opDone(it.id) }; continue }
            val jmap = Jmap(context, account)
            val payload = if (merged.size == 1) JSONObject(op.payload) else JSONObject(op.payload).put(
                "ids", JSONArray(merged.flatMap { JSONObject(it.payload).getJSONArray("ids").strings() }.distinct()),
            )
            try {
                apply(jmap, op.kind, payload)
                merged.forEach { db.opDone(it.id) }
            } catch (e: Jmap.JmapError) {
                merged.forEach { db.opDone(it.id) }
                if (op.kind == "send") Notifier.sendFailed(context, e.message.orEmpty())
            } catch (e: Exception) {
                if (op.tries >= 20) {
                    merged.forEach { db.opDone(it.id) }
                    if (op.kind == "send") Notifier.sendFailed(context, e.message.orEmpty())
                } else merged.forEach { db.opFailed(it.id) }
                ok = false
                break
            }
            touched += op.acc
        }
        val sync = MailSync(context)
        touched.forEach { key -> store.get(key)?.let { a -> runCatching { sync.sync(a) } } }
        db.touch()
        if (touched.isNotEmpty() && com.opensolr.mail.data.AppPrefs(context).signedIn) Work.indexNow(context)
        return ok
    }

    private fun mergeKey(op: MailDb.Op): String {
        val p = JSONObject(op.payload)
        return when (op.kind) {
            "seen", "flag" -> p.optBoolean("value").toString()
            "move" -> p.optString("to")
            else -> ""
        }
    }

    private suspend fun apply(jmap: Jmap, kind: String, p: JSONObject) {
        when (kind) {
            "seen", "flag" -> {
                val key = if (kind == "seen") "keywords/\$seen" else "keywords/\$flagged"
                val v: Any = if (p.getBoolean("value")) true else JSONObject.NULL
                val update = JSONObject()
                p.getJSONArray("ids").strings().forEach { update.put(it, JSONObject().put(key, v)) }
                checkSet(jmap.call("Email/set", JSONObject().put("update", update)))
            }
            "move" -> {
                val update = JSONObject()
                val to = p.getString("to")
                p.getJSONArray("ids").strings().forEach { update.put(it, JSONObject().put("mailboxIds", JSONObject().put(to, true))) }
                checkSet(jmap.call("Email/set", JSONObject().put("update", update)))
            }
            "destroy" -> checkSet(jmap.call("Email/set", JSONObject().put("destroy", p.getJSONArray("ids"))))
            "send", "draft" -> sendOrSave(jmap, kind == "send", p)
            "restore" -> {
                val update = JSONObject()
                val to = p.getString("to")
                val notJunk = p.optBoolean("notjunk")
                p.getJSONArray("ids").strings().forEach {
                    val patch = JSONObject().put("mailboxIds", JSONObject().put(to, true))
                    if (notJunk) patch.put("keywords/\$junk", JSONObject.NULL).put("keywords/\$notjunk", true)
                    update.put(it, patch)
                }
                checkSet(jmap.call("Email/set", JSONObject().put("update", update)))
            }
            "read_box" -> readBox(jmap, p.getString("box"))
            "empty_box" -> emptyBox(jmap, p.getString("box"))
        }
    }

    /**
     * Pages through the unread of a mailbox: each request marks the page found by the previous one
     * and asks for the next, so N pages cost N+1 requests and nothing is held but one page of ids.
     */
    private suspend fun readBox(jmap: Jmap, box: String) {
        val filter = JSONObject().put("inMailbox", box).put("notKeyword", "\$seen")
        var pending = emptyList<String>()
        repeat(MAX_PAGES) {
            val b = Jmap.Batch()
            var setId: String? = null
            if (pending.isNotEmpty()) {
                val update = JSONObject()
                pending.forEach { update.put(it, JSONObject().put("keywords/\$seen", true)) }
                setId = b.add("Email/set", JSONObject().put("accountId", jmap.accountId).put("update", update))
            }
            val q = b.add("Email/query", JSONObject().put("accountId", jmap.accountId).put("filter", filter).put("limit", PAGE))
            val r = jmap.send(b)
            setId?.let { checkSet(r.get(it)) }
            val next = r.get(q).getJSONArray("ids").strings()
            if (next.isEmpty() || next == pending) return
            pending = next
        }
    }

    /** Empties a mailbox page by page, with the same one-request-per-page chaining as [readBox]. */
    private suspend fun emptyBox(jmap: Jmap, box: String) {
        var destroy = emptyList<String>()
        var leave = emptyList<String>()
        repeat(MAX_PAGES) {
            val b = Jmap.Batch()
            var setId: String? = null
            if (destroy.isNotEmpty() || leave.isNotEmpty()) {
                val set = JSONObject().put("accountId", jmap.accountId)
                if (destroy.isNotEmpty()) set.put("destroy", JSONArray(destroy))
                if (leave.isNotEmpty()) {
                    val update = JSONObject()
                    leave.forEach { update.put(it, JSONObject().put("mailboxIds/$box", JSONObject.NULL)) }
                    set.put("update", update)
                }
                setId = b.add("Email/set", set)
            }
            val q = b.add("Email/query", JSONObject().put("accountId", jmap.accountId).put("filter", JSONObject().put("inMailbox", box)).put("limit", PAGE))
            val g = b.add(
                "Email/get",
                JSONObject().put("accountId", jmap.accountId).put("properties", JSONArray().put("mailboxIds"))
                    .put("#ids", JSONObject().put("resultOf", q).put("name", "Email/query").put("path", "/ids")),
            )
            val r = jmap.send(b)
            setId?.let { checkSet(r.get(it)) }
            val list = r.get(g).getJSONArray("list")
            if (list.length() == 0) return
            val d = ArrayList<String>()
            val l = ArrayList<String>()
            for (i in 0 until list.length()) {
                val e = list.getJSONObject(i)
                // A message also filed elsewhere only leaves this mailbox; everything else is destroyed.
                if ((e.optJSONObject("mailboxIds")?.length() ?: 1) > 1) l += e.getString("id") else d += e.getString("id")
            }
            if (d == destroy && l == leave) return
            destroy = d
            leave = l
        }
    }

    private suspend fun sendOrSave(jmap: Jmap, send: Boolean, p: JSONObject) {
        val acc = jmap.account.key
        val drafts = db.mailboxByRole(acc, Role.DRAFTS) ?: throw Jmap.JmapError("noDrafts", "no Drafts mailbox")
        val sent = db.mailboxByRole(acc, Role.SENT)

        val attachments = JSONArray()
        val files = p.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val file = File(f.getString("path"))
            if (!file.exists()) continue
            val blob = jmap.upload(file.readBytes(), f.optString("type"))
            attachments.put(JSONObject().put("blobId", blob).put("type", f.optString("type")).put("name", f.optString("name")).put("disposition", "attachment"))
        }

        val email = JSONObject()
            .put("mailboxIds", JSONObject().put(drafts.id, true))
            .put("keywords", JSONObject().put("\$draft", true).put("\$seen", true))
            .put("from", JSONArray().put(p.getJSONObject("from")))
            .put("to", p.getJSONArray("to"))
            .put("subject", p.optString("subject"))
            .put("bodyValues", JSONObject().put("b1", JSONObject().put("value", p.optString("text"))))
            .put("textBody", JSONArray().put(JSONObject().put("partId", "b1").put("type", "text/plain")))
        if (p.getJSONArray("cc").length() > 0) email.put("cc", p.getJSONArray("cc"))
        if (p.getJSONArray("bcc").length() > 0) email.put("bcc", p.getJSONArray("bcc"))
        if (attachments.length() > 0) email.put("attachments", attachments)
        p.optString("in_reply_to").takeIf { it.isNotBlank() }?.let { email.put("inReplyTo", JSONArray().put(it.trim('<', '>'))) }
        p.optString("references").takeIf { it.isNotBlank() }?.let { refs ->
            email.put("references", JSONArray(Regex("<([^>]+)>").findAll(refs).map { it.groupValues[1] }.toList()))
        }

        val b = Jmap.Batch(Jmap.SEND)
        val set = JSONObject().put("accountId", jmap.accountId).put("create", JSONObject().put("draft", email))
        p.optString("replaces").takeIf { it.isNotBlank() }?.let { set.put("destroy", JSONArray().put(it)) }
        val answered = p.optString("answered").takeIf { it.isNotBlank() }
        if (send && answered != null) set.put("update", JSONObject().put(answered, JSONObject().put("keywords/\$answered", true)))
        val setId = b.add("Email/set", set)
        var subId: String? = null
        if (send) {
            val onSuccess = JSONObject().put("mailboxIds/${drafts.id}", JSONObject.NULL).put("keywords/\$draft", JSONObject.NULL)
            sent?.let { onSuccess.put("mailboxIds/${it.id}", true) }
            subId = b.add(
                "EmailSubmission/set",
                JSONObject().put("accountId", jmap.accountId)
                    .put("create", JSONObject().put("sub", JSONObject().put("emailId", "#draft").put("identityId", p.getString("identity"))))
                    .put("onSuccessUpdateEmail", JSONObject().put("#sub", onSuccess)),
            )
        }
        val res = jmap.send(b)
        val setRes = res.get(setId)
        setRes.optJSONObject("notCreated")?.optJSONObject("draft")?.let { throw Jmap.JmapError(it.optString("type"), it.optString("description")) }
        subId?.let { id ->
            res.get(id).optJSONObject("notCreated")?.optJSONObject("sub")?.let { throw Jmap.JmapError(it.optString("type"), it.optString("description")) }
        }
        for (i in 0 until files.length()) runCatching { File(files.getJSONObject(i).getString("path")).delete() }
    }

    private fun checkSet(r: JSONObject) {
        listOf("notUpdated", "notDestroyed").forEach { k ->
            val bad = r.optJSONObject(k) ?: return@forEach
            val first = bad.keys().asSequence().firstOrNull() ?: return@forEach
            val e = bad.optJSONObject(first)
            if (e?.optString("type") != "notFound") throw Jmap.JmapError(e?.optString("type").orEmpty(), e?.optString("description").orEmpty())
        }
    }
}
