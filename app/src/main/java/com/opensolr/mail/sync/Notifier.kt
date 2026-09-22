package com.opensolr.mail.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.opensolr.mail.MainActivity
import com.opensolr.mail.R
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.data.Message
import com.opensolr.mail.ui.AppLanguage

/** New-mail notifications with Mark as read, Delete and Reply all; plus the few app notices. */
object Notifier {

    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_APP = "app"
    const val KEY_REPLY = "reply_text"
    private const val GROUP = "com.opensolr.mail.NEW"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val words = AppLanguage.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MAIL, words.getString(R.string.channel_mail), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_APP, words.getString(R.string.channel_app), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun tagOf(m: Message) = "mail:${m.acc}"
    fun idOf(msgId: String) = msgId.hashCode()

    fun showNew(context: Context, messages: List<Message>) {
        if (messages.isEmpty() || !allowed(context) || !AppPrefs(context).notifyNewMail) return
        ensureChannels(context)
        val db = MailDb.get(context)
        val words = AppLanguage.wrap(context)
        val store = AccountStore.get(context)
        val fresh = messages.filter { db.markNotified(it.acc, it.id) }.sortedBy { it.received }
        if (fresh.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        fresh.forEach { m ->
            val account = store.get(m.acc)
            val open = PendingIntent.getActivity(
                context, idOf(m.id),
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_ACC, m.acc).putExtra(MainActivity.EXTRA_THREAD, m.threadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val reply = RemoteInput.Builder(KEY_REPLY).setLabel(words.getString(R.string.action_reply_all)).build()
            val n = NotificationCompat.Builder(context, CHANNEL_MAIL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(m.sender?.label ?: words.getString(R.string.no_sender))
                .setContentText(m.subject.ifBlank { words.getString(R.string.no_subject) })
                .setStyle(NotificationCompat.BigTextStyle().bigText(m.subject.ifBlank { words.getString(R.string.no_subject) } + "\n" + m.preview))
                .setSubText(account?.username)
                .setWhen(m.received)
                .setShowWhen(true)
                .setColor(account?.color ?: 0)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .addExtras(android.os.Bundle().apply { putString(NotificationActions.EXTRA_MSG, m.id) })
                .addAction(0, words.getString(R.string.action_mark_read), action(context, NotificationActions.MARK_READ, m, null))
                .addAction(0, words.getString(R.string.action_delete), action(context, NotificationActions.DELETE, m, null))
                .addAction(
                    NotificationCompat.Action.Builder(0, words.getString(R.string.action_reply_all), action(context, NotificationActions.REPLY_ALL, m, true))
                        .addRemoteInput(reply).setAllowGeneratedReplies(false).build()
                )
                .build()
            try {
                nm.notify(tagOf(m), idOf(m.id), n)
            } catch (_: SecurityException) {
            }
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notify)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        try {
            nm.notify("mail-summary", 0, summary)
        } catch (_: SecurityException) {
        }
    }

    private fun action(context: Context, kind: String, m: Message, mutable: Boolean?): PendingIntent {
        val i = Intent(context, NotificationActions::class.java).setAction(kind)
            .putExtra(MainActivity.EXTRA_ACC, m.acc).putExtra(NotificationActions.EXTRA_MSG, m.id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable == true) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, (kind + m.acc + m.id).hashCode(), i, flags)
    }

    fun cancel(context: Context, acc: String, msgId: String) {
        NotificationManagerCompat.from(context).cancel("mail:$acc", idOf(msgId))
    }

    /** Messages that were read or removed elsewhere lose their notification. */
    fun cancelGone(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val db = MailDb.get(context)
        val active = nm.activeNotifications.filter { it.tag?.startsWith("mail:") == true }
        active.groupBy { it.tag.removePrefix("mail:") }.forEach { (acc, list) ->
            val ids = list.mapNotNull { it.notification.extras.getString(NotificationActions.EXTRA_MSG) }
            val held = db.messages(acc, ids).associateBy { it.id }
            list.forEach { sbn ->
                val msgId = sbn.notification.extras.getString(NotificationActions.EXTRA_MSG) ?: return@forEach
                val m = held[msgId]
                if (m == null || m.seen) nm.cancel(sbn.tag, sbn.id)
            }
        }
    }

    fun sendFailed(context: Context, reason: String) {
        if (!allowed(context)) return
        ensureChannels(context)
        val words = AppLanguage.wrap(context)
        val n = NotificationCompat.Builder(context, CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(words.getString(R.string.send_failed))
            .setContentText(reason.take(200))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("send-failed", System.currentTimeMillis().toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}
