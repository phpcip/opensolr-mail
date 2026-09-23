package com.opensolr.mail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.opensolr.mail.MainActivity
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.jmap.MailActions
import com.opensolr.mail.jmap.Replies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The three buttons on a new-mail notification. */
class NotificationActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val acc = intent.getStringExtra(MainActivity.EXTRA_ACC) ?: return
        val id = intent.getStringExtra(EXTRA_MSG) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
        val pending = goAsync()
        scope.launch(com.opensolr.mail.ui.Guard) {
            try {
                val actions = MailActions(context)
                when (intent.action) {
                    MARK_READ -> actions.setSeen(acc, listOf(id), true)
                    DELETE -> actions.delete(acc, listOf(id))
                    REPLY_ALL -> if (!reply.isNullOrBlank()) replyAll(context, acc, id, reply)
                }
                Notifier.cancel(context, acc, id)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun replyAll(context: Context, acc: String, id: String, text: String) {
        val db = MailDb.get(context)
        if (db.message(acc, id)?.bodyText == null) {
            com.opensolr.mail.data.AccountStore.get(context).get(acc)?.let { a ->
                runCatching { com.opensolr.mail.jmap.MailSync(context).fetchBody(a, id) }
            }
        }
        val m = db.message(acc, id) ?: return
        val identities = db.identities(acc)
        val d = Replies.build(Replies.Kind.REPLY_ALL, m, identities)
        val identity = d.identity ?: return
        val actions = MailActions(context)
        actions.send(
            MailActions.Outgoing(
                acc = acc,
                identityId = identity.id,
                from = com.opensolr.mail.data.Address(identity.name, identity.email),
                to = d.to,
                cc = d.cc,
                bcc = emptyList(),
                subject = d.subject,
                text = text + (if (identity.signature.isNotBlank()) "\n\n-- \n${identity.signature}" else "") + d.quote,
                inReplyTo = d.inReplyTo,
                references = d.references,
                answeredId = m.id,
                replacesDraftId = null,
                attachments = emptyList(),
            )
        )
        if (!m.seen) actions.setSeen(acc, listOf(id), true)
    }

    companion object {
        const val MARK_READ = "com.opensolr.mail.MARK_READ"
        const val DELETE = "com.opensolr.mail.DELETE"
        const val REPLY_ALL = "com.opensolr.mail.REPLY_ALL"
        const val EXTRA_MSG = "msg"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
