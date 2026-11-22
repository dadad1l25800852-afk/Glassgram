package dev.youfree.app.player

import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.youfree.app.YouFreeViewModel
import dev.youfree.app.network.RouteMode

/** Real, unmodified mobile YouTube website; it is an optional WebView page, not a cloned player. */
@Composable
fun OriginalYouTubePage(vm: YouFreeViewModel) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = vm.showOriginalYoutube && webView?.canGoBack() == true) { webView?.goBack() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={vm::closeOriginalYouTube}) { Icon(Icons.Default.ArrowBack,"Назад") }
            Text("YouTube",modifier=Modifier.weight(1f))
            Text(vm.routeHint(),style=MaterialTheme.typography.labelSmall)
            IconButton(onClick={vm::switchOriginalRoute}) { Icon(Icons.Default.Refresh,"Сменить сервер") }
            IconButton(onClick={vm.showSettings = true}) { Icon(Icons.Default.Settings,"Настройки") }
        }
        when {
            !vm.originalYoutubeReady -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
            vm.routeMode == RouteMode.UNAVAILABLE -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Text(vm.originalYoutubeError.ifBlank { "Нет соединения" })
            }
            else -> key(vm.originalYoutubeEpoch) { AndroidView(factory = { ctx ->
                WebView(ctx).also { web ->
                    webView = web
                    web.setBackgroundColor(Color.BLACK)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.mediaPlaybackRequiresUserGesture = true
                    web.settings.allowFileAccess = false
                    web.settings.allowContentAccess = false
                    web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    web.webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) vm.reportOriginalMainFrameError()
                        }
                    }
                    web.webChromeClient = WebChromeClient()
                    web.loadUrl("https://m.youtube.com/")
                }
            }, modifier=Modifier.fillMaxSize(), onRelease = {
                it.stopLoading(); it.destroy(); webView = null
            }) }
        }
    }
}
