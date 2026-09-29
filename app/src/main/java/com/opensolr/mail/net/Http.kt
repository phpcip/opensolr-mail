package com.opensolr.mail.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    /** Opensolr and Solr: never follows a redirect. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    /** The mail server: blob downloads may redirect, but only https to https. */
    val mail: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    /** Streaming answers (AI): no read timeout between chunks beyond a generous one. */
    val stream: OkHttpClient = client.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Drops the pooled connections (shared by every client above): after the phone slept they are dead and a request on one hangs until its timeout. */
    fun dropIdleConnections() {
        // Closing a TLS socket writes close_notify: never on the main thread.
        evictor.execute { runCatching { client.connectionPool.evictAll() } }
    }

    private val evictor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "http-evict").apply { isDaemon = true }
    }
}
