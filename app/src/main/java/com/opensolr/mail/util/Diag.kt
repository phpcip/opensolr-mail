package com.opensolr.mail.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain diagnostic log of the indexer on the phone, in the app's external files directory so it can be
 * read with adb: what each run did, and every failure with its cause. Kept to about 1 MB, older half dropped.
 */
object Diag {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file == null) file = File(context.getExternalFilesDir(null) ?: context.filesDir, "diag.log")
    }

    fun log(tag: String, msg: String, e: Throwable? = null) {
        val f = file ?: return
        val line = StringBuilder().append(fmt.format(Date())).append(' ').append(tag).append(": ").append(msg)
        if (e != null) line.append(" | ").append(e.javaClass.simpleName).append(": ").append(e.message ?: "").append(" @ ").append(e.stackTrace.take(4).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber })
        line.append('\n')
        synchronized(this) {
            runCatching {
                if (f.length() > 1_000_000L) {
                    val keep = f.readText().let { it.substring(it.length / 2) }
                    f.writeText(keep)
                }
                f.appendText(line.toString())
            }
        }
        android.util.Log.w(tag, line.toString())
    }
}
