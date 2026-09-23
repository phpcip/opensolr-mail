package com.opensolr.mail.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.Attachment
import com.opensolr.mail.jmap.Jmap
import kotlinx.coroutines.runBlocking
import java.io.File

/** One message body. Scripts never run; remote images only when allowed; inline images (cid:) come from the message itself. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailWebView(html: String, acc: String, attachments: List<Attachment>, remoteImages: Boolean, modifier: Modifier = Modifier, onEdgeDrag: (Float) -> Unit = {}, onEdgeFling: (Float) -> Unit = {}) {
    val edgeDrag by androidx.compose.runtime.rememberUpdatedState(onEdgeDrag)
    val edgeFling by androidx.compose.runtime.rememberUpdatedState(onEdgeFling)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val paper = com.opensolr.mail.ui.theme.LocalPalette.current.paper
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(paper.toArgb())
                // In the dark theme the message is darkened by the WebView itself, its own colours included,
                // instead of standing as a white block.
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                    androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
                }
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = false
                // A finger on the message belongs to the message: it pans in every direction at once; only past the
                // message's top or bottom does the drag go on to the conversation.
                @SuppressLint("ClickableViewAccessibility")
                var lastY = 0f
                var edge = false
                var tracker: android.view.VelocityTracker? = null
                setOnTouchListener { v, e ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            lastY = e.rawY; edge = false
                            tracker?.recycle(); tracker = android.view.VelocityTracker.obtain()
                        }
                        android.view.MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) {
                            val dy = e.rawY - lastY
                            lastY = e.rawY
                            // Past the message's top or bottom the drag moves the conversation instead.
                            edge = dy != 0f && !v.canScrollVertically(if (dy < 0) 1 else -1)
                            if (edge) edgeDrag(dy)
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            tracker?.let { t -> t.addMovement(e); t.computeCurrentVelocity(1000); if (edge) edgeFling(t.yVelocity) }
                            tracker?.recycle(); tracker = null
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null }
                    }
                    if (e.actionMasked != android.view.MotionEvent.ACTION_UP) tracker?.addMovement(android.view.MotionEvent.obtain(e).also { it.setLocation(e.rawX, e.rawY) })
                    false
                }
                webViewClient = MailClient(ctx, acc, attachments)
            }
        },
        update = { w ->
            w.setBackgroundColor(paper.toArgb())
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, dark)
            }
            w.settings.blockNetworkImage = !remoteImages
            w.settings.blockNetworkLoads = !remoteImages
            (w.webViewClient as? MailClient)?.attachments = attachments
            val doc = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>body{margin:12px;font-family:sans-serif;font-size:15px;line-height:1.45;color:#111;word-wrap:break-word;overflow-wrap:anywhere}" +
                "img{max-width:100%;height:auto}pre{white-space:pre-wrap}blockquote{margin:0 0 0 8px;padding-left:8px;border-left:2px solid #d9d4cc}" +
                "details.osq{margin-top:14px}details.osq>summary{display:inline-block;list-style:none;cursor:pointer;font-size:14px;font-weight:600;color:#a8481b;" +
                "padding:6px 12px;border:1px solid #d9d4cc;background:#f3efe9;border-radius:2px}details.osq>summary::-webkit-details-marker{display:none}" +
                "details.osq[open]>summary{margin-bottom:10px}details.osq[open]>summary .s,details.osq:not([open])>summary .h{display:none}</style></head><body>" +
                html + "</body></html>"
            if (w.tag != doc.hashCode()) {
                w.tag = doc.hashCode()
                w.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
            }
        },
    )
}

private class MailClient(private val context: Context, private val acc: String, var attachments: List<Attachment>) : WebViewClient() {

    /** After a zoom the WebView takes the height of its zoomed content, so the whole message can be reached. */
    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        view.post { view.requestLayout() }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto" || uri.scheme == "tel") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri: Uri = request.url
        if (uri.scheme != "cid") return null
        val cid = uri.schemeSpecificPart.trim('<', '>')
        val att = attachments.firstOrNull { it.cid?.trim('<', '>') == cid } ?: return empty()
        val account = AccountStore.get(context).get(acc) ?: return empty()
        return runCatching {
            val dir = File(context.cacheDir, "attachments/inline").apply { mkdirs() }
            val file = File(dir, att.blobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' })
            if (!file.exists()) runBlocking { Jmap(context, account).download(att.blobId, att.name, att.type, file) }
            WebResourceResponse(att.type.ifBlank { "image/*" }, null, file.inputStream())
        }.getOrElse { empty() }
    }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
}
