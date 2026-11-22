package dev.youfree.app.player

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * YouTube video stays inside Google's own embed. This is NOT a WebView-based
 * clone of the whole application. All feed, navigation, and settings are Compose.
 * Do not try to extract video URLs from this player into Media3.
 */
@Composable
fun YouTubeEmbeddedPlayer(videoId: String, modifier: Modifier = Modifier, startSeconds: Int = 0, onFailure: (String)->Unit) {
    val validId = remember(videoId) { videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } }
    if (validId == null) return
    val current = remember { mutableStateOf<WebView?>(null) }
    Box(modifier) {
        AndroidView(factory={ context ->
            WebView(context).also { web ->
                current.value = web
                web.addJavascriptInterface(object {
                    @JavascriptInterface fun reportStall(second: Int) {
                        web.post { onFailure("stall:${second.coerceAtLeast(0)}") }
                    }
                }, "YouFreeStatus")
                web.setBackgroundColor(Color.BLACK)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.mediaPlaybackRequiresUserGesture = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.settings.setSupportMultipleWindows(false)
                web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptThirdPartyCookies(web,true)
                web.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView?,request: WebResourceRequest?,error: WebResourceError?) {
                        if (request?.isForMainFrame == true) onFailure(error?.description?.toString() ?: "network error")
                    }
                }
                web.webChromeClient = object : WebChromeClient() {
                    var fullscreen: View? = null
                    var callback: CustomViewCallback? = null
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null || fullscreen != null) { callback?.onCustomViewHidden(); return }
                        val activity = context as? Activity ?: return
                        fullscreen=view
                        this.callback=callback
                        (activity.window.decorView as ViewGroup).addView(view,
                            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                    override fun onHideCustomView() {
                        val activity = context as? Activity
                        fullscreen?.let { (activity?.window?.decorView as? ViewGroup)?.removeView(it) }
                        fullscreen=null
                        callback?.onCustomViewHidden()
                        callback=null
                    }
                }
                // A virtual HTTPS origin gives the embedded player a Referer identifier.
                // It is NOT a server address to deploy or contact.
                web.loadDataWithBaseURL("https://dev.youfree.app/", embedHtml(validId, startSeconds),
                    "text/html", "UTF-8", null)
            }
        }, update={ }, modifier=Modifier.fillMaxSize(), onRelease={ it.stopLoading(); it.removeJavascriptInterface("YouFreeStatus"); it.destroy(); current.value = null })
        // Small edge gestures: do not obscure the central area or YouTube controls.
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(24.dp).fillMaxHeight().pointerInput(validId) {
                detectTapGestures(onDoubleTap={ current.value?.evaluateJavascript("seekBy(-10);",null) })
            })
            Box(Modifier.weight(1f))
            Box(Modifier.width(24.dp).fillMaxHeight().pointerInput(validId) {
                detectTapGestures(onDoubleTap={ current.value?.evaluateJavascript("seekBy(10);",null) })
            })
        }
    }
}

private fun embedHtml(id: String, startSeconds: Int) = """
<!doctype html><html lang="ru"><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1" />
<meta name="referrer" content="strict-origin-when-cross-origin" />
<style>html,body{margin:0;background:#000;width:100%;height:100%;overflow:hidden}
#video{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
</head><body>
<iframe id="video" src="https://www.youtube.com/embed/$id?enablejsapi=1&playsinline=1&start=${startSeconds.coerceIn(0,86400)}"
 allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
 referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
<script src="https://www.youtube.com/iframe_api"></script>
<script>var player;
var waiting;
function onStateChange(e) {
  clearTimeout(waiting);
  if (e.data === YT.PlayerState.BUFFERING) {
    waiting = setTimeout(function() {
      if (player && player.getPlayerState() === YT.PlayerState.BUFFERING)
        YouFreeStatus.reportStall(Math.floor(player.getCurrentTime() || 0));
    }, 15000);
  }
}
function onYouTubeIframeAPIReady(){player=new YT.Player('video',{events:{onStateChange:onStateChange}});}
function seekBy(seconds){if(player && player.seekTo){player.seekTo(Math.max(0,player.getCurrentTime()+seconds),true);}}
</script></body></html>
""".trimIndent()
