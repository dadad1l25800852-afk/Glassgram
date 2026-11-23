package dev.youfree.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import dev.youfree.app.data.LocalVideoStore
import dev.youfree.app.data.SettingsStore
import dev.youfree.app.data.UserSettings
import dev.youfree.app.data.Video
import dev.youfree.app.data.VideoCatalog
import dev.youfree.app.network.ConnectionMode
import dev.youfree.app.network.RouteManager
import kotlinx.coroutines.launch

private val RED = Color(0xFFFF0033)
private val BLACK = Color(0xFF0F0F0F)
private val CARD = Color(0xFF202020)
private val GREY = Color(0xFFBDBDBD)
private enum class Tab { HOME, SHORTS, SEARCH, NETWORK, LIBRARY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(15,15,15)
        window.navigationBarColor = android.graphics.Color.rgb(15,15,15)
        setContent { YouFreeApplication(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun YouFreeApplication(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(prefs.load()) }
    val history = remember { LocalVideoStore(context) }
    val routes = remember { RouteManager(context.applicationContext) }
    val connection by routes.status.collectAsState()
    val catalog = remember { VideoCatalog(routes) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var playing by remember { mutableStateOf<Video?>(null) }
    var feed by remember { mutableStateOf<List<Video>>(emptyList()) }
    var shortVideos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var searchVideos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var networkMode by remember { mutableStateOf(ConnectionMode.VPN) }
    var vpnText by remember { mutableStateOf(settings.vpnLink) }
    var dpiText by remember { mutableStateOf(settings.dpiStrategy) }
    var keyText by remember { mutableStateOf(settings.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var savedRevision by remember { mutableIntStateOf(0) }

    fun save(updated: UserSettings) {
        settings = updated
        prefs.save(updated)
    }
    fun openVideo(video: Video) {
        history.savePlayed(video)
        playing = video
    }
    fun findVideo() {
        val query = searchText.trim()
        if (query.isEmpty()) { message = "Напиши название видео"; return }
        scope.launch {
            loading = true
            message = ""
            try { searchVideos = catalog.search(settings.apiKey, query) }
            catch (e: Exception) { message = e.message ?: "Ошибка поиска" }
            finally { loading = false }
        }
    }

    LaunchedEffect(settings.apiKey, connection.revision) {
        loading = true
        message = ""
        try { feed = catalog.home(settings.apiKey) }
        catch (e: Exception) { message = "Лента: " + (e.message ?: "сеть недоступна") }
        finally { loading = false }
    }
    LaunchedEffect(tab, settings.apiKey, connection.revision) {
        if (tab == Tab.SHORTS && settings.apiKey.isNotBlank()) {
            runCatching { shortVideos = catalog.shorts(settings.apiKey) }
                .onFailure { message = it.message ?: "Shorts недоступны" }
        }
    }
    BackHandler(enabled = playing != null) { playing = null }

    val colors = darkColorScheme(primary = RED, background = BLACK,
        surface = BLACK, surfaceVariant = CARD, onBackground = Color.White,
        onSurface = Color.White, onSurfaceVariant = GREY)

    MaterialTheme(colorScheme = colors) {
        if (playing != null) {
            PlayerPage(activity, playing!!,
                onBack = { playing = null },
                onSave = {
                    history.toggleSaved(playing!!)
                    savedRevision++
                },
                isSaved = history.saved().any { it.id == playing!!.id },
                routeRevision = connection.revision)
        } else {
            Scaffold(containerColor = BLACK, topBar = {
                Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayCircleFilled, "YouFree", tint = RED,
                        modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("YouFree", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (connection.mode != ConnectionMode.DIRECT) {
                        Icon(Icons.Default.Shield, "Маршрут подключён", tint = Color(0xFF68D391))
                        Spacer(Modifier.width(10.dp))
                    }
                    IconButton(onClick = { tab = Tab.SEARCH }) {
                        Icon(Icons.Default.Search, "Поиск", tint = Color.White)
                    }
                    IconButton(onClick = { tab = Tab.NETWORK }) {
                        Icon(Icons.Default.Settings, "Сеть", tint = Color.White)
                    }
                }
            }, bottomBar = {
                NavigationBar(containerColor = BLACK, tonalElevation = 0.dp) {
                    listOf(
                        Triple(Tab.HOME, "Главная", Icons.Default.Home),
                        Triple(Tab.SHORTS, "Shorts", Icons.Default.SmartDisplay),
                        Triple(Tab.SEARCH, "Поиск", Icons.Default.Search),
                        Triple(Tab.NETWORK, "Сеть", Icons.Default.Wifi),
                        Triple(Tab.LIBRARY, "Вы", Icons.Default.AccountCircle)
                    ).forEach { (target, title, icon) ->
                        NavigationBarItem(
                            selected = tab == target,
                            onClick = { tab = target },
                            icon = { Icon(icon, contentDescription = title) },
                            label = { Text(title, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color.White,
                                indicatorColor = CARD,
                                unselectedIconColor = GREY,
                                unselectedTextColor = GREY
                            )
                        )
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        Tab.HOME -> Column {
                            Row(Modifier.horizontalScrollCompat().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                listOf("Все", "Музыка", "Игры", "Новости", "Прямые эфиры")
                                    .forEach { tag ->
                                        Surface(shape = RoundedCornerShape(9.dp), color = CARD) {
                                            Text(tag, Modifier.padding(horizontal = 13.dp,
                                                vertical = 8.dp), color = Color.White,
                                                fontSize = 13.sp)
                                        }
                                    }
                            }
                            if (feed.isEmpty()) {
                                EmptyFeed(message.ifBlank {
                                    if (loading) "Загружаю настоящие видео…"
                                    else "Не удалось получить видео. Проверь сеть или настрой API."
                                }, onNetwork = { tab = Tab.NETWORK })
                            } else {
                                VideoFeed(feed, ::openVideo)
                            }
                        }

                        Tab.SEARCH -> Column {
                            Row(Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("Искать на YouTube") }
                                )
                                IconButton(onClick = ::findVideo) {
                                    Icon(Icons.Default.Search, "Найти")
                                }
                            }
                            if (message.isNotBlank()) {
                                Text(message, Modifier.padding(15.dp), color = GREY)
                            }
                            if (searchVideos.isEmpty()) {
                                EmptyFeed(
                                    if (settings.apiKey.isBlank())
                                        "Для настоящего поиска нужен YouTube Data API key. Добавь его на вкладке «Сеть»."
                                    else "Напиши запрос и нажми поиск.",
                                    { tab = Tab.NETWORK }
                                )
                            } else VideoFeed(searchVideos, ::openVideo)
                        }

                        Tab.SHORTS -> {
                            if (shortVideos.isEmpty()) {
                                EmptyFeed(
                                    "Shorts: для загрузки коротких роликов нужен YouTube Data API key. Это поиск коротких видео, не официальный Shorts feed.",
                                    { tab = Tab.NETWORK }
                                )
                            } else {
                                val pager = rememberPagerState(pageCount = { shortVideos.size })
                                VerticalPager(state = pager) { index ->
                                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                                        PlayerEmbed(activity, shortVideos[index].id,
                                            connection.revision, modifier = Modifier.fillMaxSize())
                                        Text(shortVideos[index].title,
                                            Modifier.align(Alignment.BottomStart)
                                                .padding(20.dp)
                                                .background(Color(0x99000000))
                                                .padding(9.dp),
                                            maxLines = 2)
                                    }
                                }
                            }
                        }

                        Tab.NETWORK -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Сеть и подключение", fontSize = 25.sp,
                                fontWeight = FontWeight.Bold)
                            Surface(color = CARD, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ТЕКУЩЕЕ СОЕДИНЕНИЕ",
                                        style = MaterialTheme.typography.labelSmall, color = GREY)
                                    Text(connection.text, fontWeight = FontWeight.SemiBold)
                                    if (connection.videoTestPassed == false) {
                                        Text("Проверка YouTube не прошла. Это не означает, что видео точно заработает.",
                                            color = Color(0xFFFFB86B), fontSize = 12.sp)
                                    }
                                    OutlinedButton(onClick = { scope.launch { routes.direct() } },
                                        enabled = !connection.busy) { Text("Прямое подключение / отключить") }
                                }
                            }
                            Text("Выбери способ", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = networkMode == ConnectionMode.VPN,
                                    onClick = { networkMode = ConnectionMode.VPN },
                                    label = { Text("VPN / Xray") }
                                )
                                FilterChip(
                                    selected = networkMode == ConnectionMode.BYEDPI,
                                    onClick = { networkMode = ConnectionMode.BYEDPI },
                                    label = { Text("ByeByeDPI") }
                                )
                            }
                            if (networkMode == ConnectionMode.VPN) {
                                Text("Встроенный Xray. Вставь VLESS, Trojan, Shadowsocks или HTTPS-подписку. Выбирается доступный сервер с минимальной задержкой.",
                                    fontSize = 13.sp, color = GREY)
                                OutlinedTextField(
                                    value = vpnText, onValueChange = { vpnText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Ссылка на VPN / подписку") },
                                    placeholder = { Text("vless://… или https://…") },
                                    minLines = 2, maxLines = 4
                                )
                                Button(
                                    onClick = {
                                        save(settings.copy(vpnLink = vpnText.trim()))
                                        scope.launch { routes.vpn(vpnText) }
                                    },
                                    enabled = !connection.busy && vpnText.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Bolt, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Проверить и подключить")
                                }
                            } else {
                                Text("Настоящий ByeDPI включён в APK. Никаких отдельных приложений и root. Укажи параметры запуска локального DPI-движка.",
                                    fontSize = 13.sp, color = GREY)
                                OutlinedTextField(
                                    value = dpiText, onValueChange = { dpiText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Параметры ByeDPI") },
                                    placeholder = { Text("--tlsrec 3+s") },
                                    minLines = 2, maxLines = 3
                                )
                                Button(
                                    onClick = {
                                        save(settings.copy(dpiStrategy = dpiText))
                                        scope.launch { routes.byedpi(dpiText) }
                                    },
                                    enabled = !connection.busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Bolt, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Включить ByeDPI")
                                }
                                Text("Работает только в YouFree. Некоторые провайдеры блокируют видео и после обхода DPI.",
                                    fontSize = 12.sp, color = GREY)
                            }
                            HorizontalDivider(color = CARD)
                            Text("Данные YouTube", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = keyText, onValueChange = { keyText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("YouTube Data API v3 key") },
                                visualTransformation = if (showApiKey)
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                else PasswordVisualTransformation()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = showApiKey,
                                    onCheckedChange = { showApiKey = it })
                                Text("Показать API key")
                            }
                            OutlinedButton(onClick = {
                                save(settings.copy(apiKey = keyText.trim()))
                                message = "Ключ сохранён. Вернись на главную."
                            }) { Text("Сохранить ключ для ленты и поиска") }
                            Text("Проигрыватель использует официальный YouTube iframe. Google-авторизация, лайки и премиум-функции не реализованы.",
                                fontSize = 12.sp, color = GREY)
                        }

                        Tab.LIBRARY -> Column {
                            Text("Ваша библиотека", Modifier.padding(16.dp),
                                fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("Сохранённое", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                fontWeight = FontWeight.SemiBold)
                            val saved = remember(savedRevision) { history.saved() }
                            if (saved.isNotEmpty()) VideoFeed(saved, ::openVideo, Modifier.weight(1f))
                            else {
                                Text("Добавляй ролики из проигрывателя кнопкой «Сохранить».",
                                    Modifier.padding(16.dp), color = GREY)
                                Text("История просмотров", Modifier.padding(16.dp),
                                    fontWeight = FontWeight.SemiBold)
                                val viewed = history.history()
                                if (viewed.isNotEmpty()) VideoFeed(viewed, ::openVideo)
                            }
                        }
                    }
                    if (loading && tab == Tab.HOME) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter),
                            color = RED)
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(
    Modifier.horizontalScroll(rememberScrollState())
)

@Composable
private fun EmptyFeed(message: String, onNetwork: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.PlayCircleOutline, null,
            modifier = Modifier.size(50.dp), tint = RED)
        Text(message, color = GREY, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onNetwork) {
            Icon(Icons.Default.Wifi, null)
            Spacer(Modifier.width(8.dp))
            Text("Настроить подключение")
        }
    }
}

@Composable
private fun VideoFeed(
    videos: List<Video>,
    onPlay: (Video) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier) {
        items(videos, key = { it.id }) { video ->
            Column(Modifier.fillMaxWidth().clickable { onPlay(video) }
                .padding(bottom = 19.dp)) {
                AsyncImage(
                    model = video.thumbnail, contentDescription = video.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop
                )
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(CARD),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, tint = RED)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(video.title, fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(video.channel, color = GREY, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerPage(
    activity: Activity, video: Video, onBack: () -> Unit,
    onSave: () -> Unit, isSaved: Boolean, routeRevision: Int
) {
    Column(Modifier.fillMaxSize().background(BLACK)) {
        Row(Modifier.height(54.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text("YouFree", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        }
        PlayerEmbed(activity, video.id, routeRevision,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text(video.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(video.channel, color = GREY)
            Button(onClick = onSave) {
                Icon(if (isSaved) Icons.Default.CheckCircle else Icons.Default.BookmarkAdd, null)
                Spacer(Modifier.width(8.dp))
                Text(if (isSaved) "Сохранено" else "Сохранить")
            }
            Text("Это официальный встраиваемый плеер YouTube. Если видео не разрешено к встраиванию, воспроизведение может быть недоступно.",
                color = GREY, fontSize = 12.sp)
        }
    }
}

/**
 * Native screen with official YouTube iframe WebView.
 * This is not raw YouTube video extraction or a rebranded copy of Google APK.
 */
@Composable
private fun PlayerEmbed(activity: Activity, videoId: String, routeRevision: Int,
                        modifier: Modifier = Modifier) {
    key(videoId, routeRevision) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        var custom: View? = null
                        var callback: CustomViewCallback? = null

                        override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                            if (view == null || custom != null) { cb?.onCustomViewHidden(); return }
                            custom = view
                            callback = cb
                            val decor = activity.window.decorView as ViewGroup
                            decor.addView(view, ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT))
                            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                            activity.requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        override fun onHideCustomView() {
                            val view = custom ?: return
                            (view.parent as? ViewGroup)?.removeView(view)
                            callback?.onCustomViewHidden()
                            custom = null
                            callback = null
                            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                            activity.requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                    loadUrl("https://www.youtube.com/embed/" + videoId +
                        "?playsinline=1&rel=0", mapOf("Referer" to "https://www.youtube.com/"))
                }
            },
            modifier = modifier,
            onRelease = { web ->
                web.stopLoading()
                web.loadUrl("about:blank")
                web.destroy()
            }
        )
    }
}
