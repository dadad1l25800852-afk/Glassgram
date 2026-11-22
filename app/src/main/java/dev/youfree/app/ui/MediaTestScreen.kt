package dev.youfree.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import dev.youfree.app.YouFreeViewModel

/** Media3 is reserved for URLs for which the user holds playback rights. */
@Composable
fun MediaTestScreen(vm: YouFreeViewModel,onBack:()->Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(vm.config.servers.firstOrNull()?.trimEnd('/')?.plus("/api/v1/media/playlist.m3u8") ?: "") }
    var activeUrl by remember { mutableStateOf("") }
    var speed by remember { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Назад")}
            Text("Media3 / ExoPlayer",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(10.dp))
        }
        Text("Только разрешённые HLS, DASH и MP4-потоки. Это не способ извлечения YouTube-видео.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value=url,onValueChange={url=it},modifier=Modifier.fillMaxWidth(),
            label={Text("HTTPS-ссылка на свой поток")},minLines=2)
        Button(onClick={
            if (runCatching { Uri.parse(url).scheme == "https" }.getOrDefault(false)) activeUrl=url
        },modifier=Modifier.padding(vertical=8.dp)) {Text("Воспроизвести")}
        if(activeUrl.isNotBlank()) {
            key(activeUrl) {
                val base = vm.config.servers.firstOrNull { activeUrl.startsWith(it.trimEnd('/') + "/") }
                val factory = remember(activeUrl) {
                    DefaultHttpDataSource.Factory().setConnectTimeoutMs(9000).setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(false)
                        .apply { if(base!=null) setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${vm.config.token}")) }
                }
                val player=remember(activeUrl) {
                    ExoPlayer.Builder(context)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                            setMediaItem(MediaItem.fromUri(activeUrl))
                            prepare();playWhenReady=true
                        }
                }
                DisposableEffect(player) { onDispose {player.release()} }
                AndroidView(factory={ctx->PlayerView(ctx).apply {this.player=player;useController=true}},
                    modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    listOf(.75f,1f,1.25f,1.5f,2f).forEach {choice->
                        FilterChip(selected=choice==speed,onClick={speed=choice;player.setPlaybackSpeed(choice)},label={Text("${choice}×")})
                    }
                }
                Text("Адаптивное качество определяется доступными дорожками и возможностями устройства.",
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
