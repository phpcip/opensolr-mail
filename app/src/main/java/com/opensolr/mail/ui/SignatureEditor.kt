package com.opensolr.mail.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.opensolr.mail.R
import com.opensolr.mail.data.Signature
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun Color.hex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

/**
 * The signature written as it will look: a box where the words are edited in place, and under it bold, italic,
 * underline, link and clear. Every change is cleaned to those few marks and handed to [onChange].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun SignatureEditor(initial: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChange)
    val bring = remember { BringIntoViewRequester() }
    var web by remember { mutableStateOf<WebView?>(null) }
    var marks by remember { mutableStateOf(setOf<String>()) }
    // The link being added or changed: its words and address; null while no link dialog is open.
    var linking by remember { mutableStateOf<Pair<String, String>?>(null) }
    val hint = stringResource(R.string.signature_hint)

    fun js(code: String) { web?.evaluateJavascript(code, null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(170.dp).border(1.dp, p.hairline).bringIntoViewRequester(bring)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(p.paper.toArgb())
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        // A link in the editor is only edited; it never opens from here.
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        }
                        addJavascriptInterface(object {
                            @JavascriptInterface fun changed(html: String) { post { changed(Signature.clean(html)) } }
                            @JavascriptInterface fun state(list: String) { post { marks = list.split(',').filter { it.isNotEmpty() }.toSet() } }
                            @JavascriptInterface fun focused() { post { scope.launch { delay(350); bring.bringIntoView() } } }
                        }, "Sig")
                        loadDataWithBaseURL("file:///android_res/", page(initial, hint, p.ink.hex(), p.muted.hex(), p.accent.hex(), p.paper.hex()), "text/html", "utf-8", null)
                        web = this
                    }
                },
            )
        }
        ToolRow(listOf(
            Tool(R.drawable.ic_fmt_bold, stringResource(R.string.sig_bold), active = "b" in marks) { js("cmd('bold')") },
            Tool(R.drawable.ic_fmt_italic, stringResource(R.string.sig_italic), active = "i" in marks) { js("cmd('italic')") },
            Tool(R.drawable.ic_fmt_underline, stringResource(R.string.sig_underline), active = "u" in marks) { js("cmd('underline')") },
            Tool(R.drawable.ic_link, stringResource(R.string.sig_link), active = "a" in marks) {
                web?.evaluateJavascript("ask()") { r ->
                    val o = runCatching { JSONObject(JSONArray("[$r]").getString(0)) }.getOrNull() ?: JSONObject()
                    linking = o.optString("text") to o.optString("href")
                }
            },
            Tool(R.drawable.ic_fmt_clear, stringResource(R.string.sig_clear)) { js("clean()") },
        ))
        Spacer(Modifier.height(2.dp))
    }

    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }

    linking?.let { (startText, startHref) ->
        var text by remember(startText, startHref) { mutableStateOf(startText) }
        var href by remember(startText, startHref) { mutableStateOf(startHref) }
        val url = linkTarget(href)
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text(stringResource(R.string.sig_link)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(text, { text = it }, stringResource(R.string.link_text), Modifier.fillMaxWidth().border(1.dp, p.hairline))
                    Field(href, { href = it }, stringResource(R.string.link_address), Modifier.fillMaxWidth().border(1.dp, p.hairline), email = true)
                }
            },
            confirmButton = {
                DialogButton(stringResource(R.string.save), {
                    js("link(${JSONObject.quote(url)}, ${JSONObject.quote(text.trim())})")
                    linking = null
                }, accent = true, enabled = url.isNotEmpty())
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    if (startHref.isNotEmpty()) DialogButton(stringResource(R.string.remove), { js("unlink()"); linking = null })
                    DialogButton(stringResource(R.string.cancel), { linking = null })
                }
            },
            containerColor = p.paper,
            titleContentColor = p.ink,
            textContentColor = p.ink,
        )
    }
}

/** What was typed as a link address, made one: a mail address gets mailto:, a bare site https://; anything else is refused. */
private fun linkTarget(typed: String): String {
    val t = typed.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return ""
    val lower = t.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") || lower.startsWith("tel:") -> t
        Regex("^[^@/:]+@[^@/:]+\\.[^@/:]+$").matches(t) -> "mailto:$t"
        Regex("^\\+?[0-9 ().-]{6,}$").matches(t) -> "tel:$t"
        lower.contains(':') -> ""
        t.contains('.') -> "https://$t"
        else -> ""
    }
}

/** The editing page: one editable block, the app's font and colours, and the few commands the tools call. */
private fun page(initial: String, hint: String, ink: String, muted: String, accent: String, paper: String): String = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
@font-face{font-family:SG;src:url('font/space_grotesk_medium.ttf');font-weight:400 600}
@font-face{font-family:SG;src:url('font/space_grotesk_bold.ttf');font-weight:700 900}
html,body{margin:0;padding:0;height:100%;background:$paper}
#e{min-height:100%;box-sizing:border-box;padding:10px 12px;outline:none;color:$ink;font:500 15px/1.45 SG,sans-serif;overflow-wrap:break-word;-webkit-user-select:text}
#e:empty:before{content:attr(data-hint);color:$muted}
a{color:$accent}
</style></head><body>
<div id="e" contenteditable="true"></div>
<script>
var e=document.getElementById('e'),r=null,t=null;
e.setAttribute('data-hint',${JSONObject.quote(hint)});
e.innerHTML=${JSONObject.quote(initial)};
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
function send(){clearTimeout(t);t=setTimeout(function(){Sig.changed(e.innerHTML);},250);}
function within(n){return n&&(n===e||e.contains(n));}
function back(){e.focus();if(r){var s=getSelection();s.removeAllRanges();s.addRange(r);}}
function linkAt(){var s=getSelection();var n=s.rangeCount?s.anchorNode:null;while(n&&n!==e){if(n.nodeName==='A')return n;n=n.parentNode;}return null;}
function report(){var l=[];try{if(document.queryCommandState('bold'))l.push('b');if(document.queryCommandState('italic'))l.push('i');if(document.queryCommandState('underline'))l.push('u');}catch(x){}if(linkAt())l.push('a');Sig.state(l.join(','));}
document.addEventListener('selectionchange',function(){var s=getSelection();if(s.rangeCount&&within(s.anchorNode)){r=s.getRangeAt(0).cloneRange();report();}});
e.addEventListener('input',send);
e.addEventListener('focus',function(){Sig.focused();});
e.addEventListener('paste',function(v){v.preventDefault();var x=(v.clipboardData||window.clipboardData).getData('text/plain');document.execCommand('insertText',false,x);});
function cmd(c){back();document.execCommand(c,false,null);send();report();}
function ask(){back();var a=linkAt();return JSON.stringify({href:a?a.getAttribute('href'):'',text:a?a.textContent:getSelection().toString()});}
function link(u,w){back();var a=linkAt();
 if(a){a.setAttribute('href',u);if(w&&w!==a.textContent)a.textContent=w;}
 else{var s=getSelection();if(s.isCollapsed||!s.toString()){document.execCommand('insertHTML',false,'<a href="'+esc(u)+'">'+esc(w||u.replace(/^(mailto|tel):/,''))+'</a>&nbsp;');}
 else if(w&&w!==s.toString()){document.execCommand('insertHTML',false,'<a href="'+esc(u)+'">'+esc(w)+'</a>');}
 else{document.execCommand('createLink',false,u);}}
 send();report();}
function unlink(){back();var a=linkAt();if(a){var f=document.createDocumentFragment();while(a.firstChild)f.appendChild(a.firstChild);a.parentNode.replaceChild(f,a);}else{document.execCommand('unlink');}send();report();}
function clean(){back();var s=getSelection();if(s.isCollapsed){var g=document.createRange();g.selectNodeContents(e);s.removeAllRanges();s.addRange(g);}document.execCommand('removeFormat');document.execCommand('unlink');send();report();}
</script></body></html>
"""
