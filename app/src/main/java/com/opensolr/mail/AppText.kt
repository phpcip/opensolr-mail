package com.opensolr.mail

import android.annotation.SuppressLint
import android.content.Context

/** The app's words for code that runs outside a screen — the network layer and the failures it raises. */
@SuppressLint("StaticFieldLeak")
object AppText {

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var app: Context? = null

    /** Called once by the activity as it starts, before anything can raise a failure. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    /** The string [id], filled with [args]. */
    fun s(id: Int, vararg args: Any): String = app?.getString(id, *args) ?: ""
}
