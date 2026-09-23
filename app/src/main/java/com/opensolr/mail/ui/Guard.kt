package com.opensolr.mail.ui

import kotlinx.coroutines.CoroutineExceptionHandler

/** Catches what a background task of the app throws, so a failed read or write is logged and never closes the app. */
val Guard = CoroutineExceptionHandler { _, e -> android.util.Log.w("OpensolrMail", "background task failed", e) }

/** Runs [block], logging what it throws instead of letting it close the app; a cancelled task still stops. */
suspend fun guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("OpensolrMail", "screen task failed", e)
    }
}
