package com.opensolr.mail.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opensolr.mail.R
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.index.MailIndexer
import com.opensolr.mail.jmap.MailActions
import com.opensolr.mail.jmap.MailSync
import com.opensolr.mail.push.MailPush
import com.opensolr.mail.ui.AppLanguage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/** Everything that runs in the background goes through WorkManager, so it survives the app being closed and waits for a network. */
object Work {

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun runOps(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ops", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OpsWorker>().setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build(),
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
    }

    fun index(context: Context, continuation: Boolean = false) {
        if (AppPrefs(context).indexStopped) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "index", if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS).build(),
        )
    }

    /** Starts the indexer now: a run waiting out a retry delay is replaced, a running one is left alone. Call off the main thread. */
    fun indexNow(context: Context) {
        AppPrefs(context).indexStopped = false
        val infos = runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork("index").get() }.getOrDefault(emptyList())
        if (infos.any { it.state == androidx.work.WorkInfo.State.RUNNING }) return
        // Expedited: the system starts it at once instead of when it sees fit.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "index", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS).build(),
        )
    }

    /** The safety net under push: a sync every 15 minutes. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    /** Stops indexing now and keeps it stopped. */
    fun stopIndex(context: Context) {
        AppPrefs(context).indexStopped = true
        WorkManager.getInstance(context).cancelUniqueWork("index")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
    }
}

class OpsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (MailActions(applicationContext).runQueue()) Result.success() else Result.retry()
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (AccountStore.get(ctx).all().isEmpty()) return Result.success()
        val arrived = MailSync(ctx).syncAll()
        Notifier.showNew(ctx, arrived)
        runCatching { Notifier.cancelGone(ctx) }
        runCatching { MailPush.ensure(ctx) }
        if (AppPrefs(ctx).signedIn) Work.index(ctx)
        return Result.success()
    }
}

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!AppPrefs(ctx).signedIn || AppPrefs(ctx).indexStopped) return Result.success()
        // The notification appears only once there is real work, and changes at most every 10 seconds:
        // a run with nothing to do never flashes it, and a busy one does not flicker.
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch(com.opensolr.mail.ui.Guard) {
            var shown = false
            var last = 0L
            var busySince = 0L
            MailIndexer.status.collect { st ->
                if (st.phase == MailIndexer.Phase.IDLE) return@collect
                val now = System.currentTimeMillis()
                if (busySince == 0L) busySince = now
                // A few new messages are done in seconds and never show; only a long job gets the notification.
                if (!shown && now - busySince < 15_000L) return@collect
                if (shown && now - last < 10_000L) return@collect
                shown = true
                last = now
                goForeground(st)
            }
        }
        // Check again even when the status stops changing, so a long quiet stretch still shows.
        val ticker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch(com.opensolr.mail.ui.Guard) {
            kotlinx.coroutines.delay(16_000L)
            val st = MailIndexer.status.value
            if (st.phase != MailIndexer.Phase.IDLE) goForeground(st)
        }
        return try {
            // Without the foreground notification Android stops a job after 10 minutes, so a run ends by
            // itself before that and the next one carries on; one that got the notification goes on for 30.
            when (MailIndexer(ctx).run(deadline = System.currentTimeMillis() + BACKGROUND_RUN_MS)) {
                MailIndexer.Outcome.DONE -> Result.success()
                MailIndexer.Outcome.MORE -> { Work.index(ctx, continuation = true); Result.success() }
                MailIndexer.Outcome.RETRY -> Result.retry()
            }
        } finally {
            watcher.cancel()
            ticker.cancel()
        }
    }

    private val startedAt = System.currentTimeMillis()

    /** Android refuses the notification while the app is in the background; the run then stays within the background limit. */
    private suspend fun goForeground(st: MailIndexer.Status) {
        // The refusal happens later, inside WorkManager's service, so success is judged by whether the app is on screen.
        val visible = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
            .importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        if (runCatching { setForeground(foreground(st)) }.isSuccess && visible) MailIndexer.extendRun(startedAt + FOREGROUND_RUN_MS)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(MailIndexer.status.value)

    private fun foreground(s: MailIndexer.Status): ForegroundInfo {
        Notifier.ensureChannels(applicationContext)
        val words = AppLanguage.wrap(applicationContext)
        fun n(v: Long) = String.format(java.util.Locale.US, "%,d", v)
        val title = words.getString(when (s.phase) {
            MailIndexer.Phase.MAIL -> R.string.idx_phase_mail
            MailIndexer.Phase.HISTORY -> R.string.idx_phase_history
            MailIndexer.Phase.ATTACHMENTS -> R.string.idx_phase_attachments
            MailIndexer.Phase.IDLE -> R.string.indexing
        })
        // Everything still left at Fastmail; the bar is the one of the stage at work, attachments or messages.
        val text = if (s.messagesLeft >= 0 && s.attLeft >= 0) words.getString(R.string.notif_left, n(s.messagesLeft), n(s.attLeft)) else null
        val progress = if (s.phase == MailIndexer.Phase.ATTACHMENTS) s.attachmentProgress else s.messageProgress
        val n = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (progress != null) setProgress(1000, (progress.first * 1000 / progress.second).toInt(), false) }
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(42, n)
    }

    private companion object {
        const val BACKGROUND_RUN_MS = 8 * 60_000L
        const val FOREGROUND_RUN_MS = 30 * 60_000L
    }
}
