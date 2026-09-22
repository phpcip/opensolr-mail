package com.opensolr.mail.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opensolr.mail.MainActivity

/** Where the browser hands a sign-in back (Opensolr or Fastmail); forwards the URI and closes. */
class AuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (data != null) putExtra(MainActivity.EXTRA_AUTH_CALLBACK, data.toString())
        })
        finish()
    }
}
