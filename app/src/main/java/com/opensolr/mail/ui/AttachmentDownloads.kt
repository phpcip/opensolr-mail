package com.opensolr.mail.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import com.opensolr.mail.auth.FastmailAuth
import com.opensolr.mail.data.Attachment
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.jmap.Jmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Attachments go through the phone's own Download Manager: the whole file lands in Downloads, the system
 * notification shows the progress and, once done, opens the file with a tap. Opening from the app waits
 * for the download to finish and hands the saved file to the app that reads it.
 */
object AttachmentDownloads {

    sealed class Result {
        data class Done(val uri: Uri, val type: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** The file name as saved: the attachment's own name, without anything a path could be made of. */
    fun fileName(a: Attachment): String =
        a.name.ifBlank { "attachment" }.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120)

    /** The type to save and open the file as: from its name when the message only says octet-stream. */
    fun typeOf(a: Attachment): String {
        val declared = a.type.substringBefore(';').trim().lowercase()
        val ext = a.name.substringAfterLast('.', "").lowercase()
        val byName = if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return if (declared.isEmpty() || declared == "application/octet-stream") byName ?: "application/octet-stream" else declared
    }

    /** Downloads [a] into Downloads and waits for it; a file already downloaded from the same blob is reused. */
    suspend fun download(context: Context, account: MailAccount, a: Attachment): Result = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return@withContext Result.Failed("Download Manager unavailable")
        val url = Jmap(context, account).downloadUrlOf(a.blobId, a.name, a.type)
        val type = typeOf(a)
        existing(dm, url)?.let { return@withContext Result.Done(it, type) }
        val token = FastmailAuth.accessToken(context, account.key, false)
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Authorization", "Bearer $token")
            .setTitle(fileName(a))
            .setMimeType(type)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName(a))
        val id = dm.enqueue(request)
        while (true) {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@withContext Result.Failed("Download cancelled")
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getUriForDownloadedFile(id) ?: return@withContext Result.Failed("Downloaded file not found")
                        return@withContext Result.Done(uri, type)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        return@withContext Result.Failed("Download failed ($reason)")
                    }
                    else -> Unit
                }
            }
            delay(300)
        }
        @Suppress("UNREACHABLE_CODE")
        Result.Failed("Download failed")
    }

    /** A finished download of the same blob whose file is still there. */
    private fun existing(dm: DownloadManager, url: String): Uri? {
        dm.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))?.use { c ->
            val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) {
                if (c.getString(uriCol) == url) {
                    val uri = dm.getUriForDownloadedFile(c.getLong(idCol)) ?: continue
                    return uri
                }
            }
        }
        return null
    }

    /** Opens the saved file in the app that reads its type; false when the phone has none. */
    fun open(context: Context, uri: Uri, type: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
