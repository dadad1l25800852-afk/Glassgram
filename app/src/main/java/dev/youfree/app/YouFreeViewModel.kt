package dev.youfree.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.youfree.app.data.ApiClient
import dev.youfree.app.data.AppConfig
import dev.youfree.app.data.Comment
import dev.youfree.app.data.ConfigStore
import dev.youfree.app.data.Feed
import dev.youfree.app.data.LocalLibrary
import dev.youfree.app.data.Video
import dev.youfree.app.network.RouteEngine
import dev.youfree.app.network.RouteMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration

enum class Screen { HOME, SHORTS, SUBSCRIPTIONS, LIBRARY, SEARCH, CHANNEL }

class YouFreeViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)
    private val library = LocalLibrary(application)
    val api = ApiClient()
    private val route = RouteEngine(application, api)
    var config by mutableStateOf(configStore.load())
        private set
    var screen by mutableStateOf(Screen.HOME)
    var playing by mutableStateOf<Video?>(null)
        private set
    var showSettings by mutableStateOf(false)
    var showOriginalYoutube by mutableStateOf(false)
        private set
    var originalYoutubeReady by mutableStateOf(false)
        private set
    var originalYoutubeError by mutableStateOf("")
        private set
    var originalYoutubeEpoch by mutableStateOf(0)
        private set
    private var youtubeAutoRetries = 0
    var showMediaTest by mutableStateOf(false)
    var routeMode by mutableStateOf(RouteMode.UNAVAILABLE)
        private set
    var playbackError by mutableStateOf("")
        private set
    var playerReady by mutableStateOf(false)
        private set
    var playbackEpoch by mutableStateOf(0)
        private set
    var resumeSeconds by mutableStateOf(0)
        private set
    private var automaticRetries = 0
    private var shortsRetries = 0
    var themeDark by mutableStateOf(config.dark)
        private set
    var happBusy by mutableStateOf(false)
        private set
    var happStatus by mutableStateOf("")
        private set
    val happNodes = mutableStateListOf<Pair<String, Long?>>()
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var query by mutableStateOf("")
    var selectedChannel by mutableStateOf<Pair<String, String>?>(null)
    val videos = mutableStateListOf<Video>()
    val shorts = mutableStateListOf<Video>()
    val channelVideos = mutableStateListOf<Video>()
    val comments = mutableStateListOf<Comment>()
    val history = mutableStateListOf<Video>().apply { addAll(library.getVideos("history")) }
    val watchLater = mutableStateListOf<Video>().apply { addAll(library.getVideos("watchLater")) }
    val subscriptions = mutableStateListOf<String>().apply { addAll(library.getSubscriptions()) }
    private var currentJob: Job? = null

    init {
        reconnect()
        // On subsequent launches, open the familiar YouTube site automatically
        // when the user has previously supplied a compatible subscription.
        if (config.happLink.isNotBlank()) openOriginalYouTube()
    }

    fun reconnect() {
        config = configStore.load()
        api.servers = config.servers
        api.token = config.token
        if (!config.valid()) {
            message = "Для собственной ленты нужен API-сервер. Обычный YouTube открывается кнопкой с глобусом — вставь ссылку Happ в настройках."
            return
        }
        loadHome()
    }

    fun saveConfiguration(serversText: String, secret: String): Boolean {
        val c = AppConfig(serversText.lineSequence().map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }.distinct().take(5).toList(), secret.trim(), themeDark, config.happLink)
        if (!c.valid()) {
            message = "Укажи 1–5 HTTPS-адресов (по одному в строке) и токен от 24 символов."
            return false
        }
        configStore.save(c)
        showSettings = false
        reconnect()
        return true
    }
    /** Happ link stays on device encrypted with the Keystore; no external backend receives it. */
    fun setHappLink(text: String) {
        val value = text.trim()
        if (value.isBlank()) {
            config = config.copy(happLink = "")
            configStore.save(config)
            happNodes.clear()
            happStatus = "Подписка удалена. Доступно прямое подключение или свой ретранслятор."
            return
        }
        if (happBusy) return
        happBusy = true
        happStatus = "Проверяю серверы через YouTube…"
        viewModelScope.launch {
            try {
                val result = route.importHapp(value)
                happNodes.clear(); happNodes.addAll(result)
                config = config.copy(happLink = value)
                configStore.save(config)
                happStatus = "Выбран: ${result.first().first} · ${result.first().second} мс. Настройка сохранена."
                showSettings = false
                openOriginalYouTube()
            } catch (e: Exception) {
                happStatus = "Не получилось подключить: ${e.message ?: "ошибка сети или ядра"}"
            } finally { happBusy = false }
        }
    }

    fun toggleTheme() {
        themeDark = !themeDark
        config = config.copy(dark = themeDark)
        configStore.save(config)
    }

    private fun request(block: suspend () -> Feed, saveTo: androidx.compose.runtime.snapshots.SnapshotStateList<Video>) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            busy = true; message = ""
            try {
                val result = block()
                saveTo.clear(); saveTo.addAll(result.videos)
                if (result.videos.isEmpty()) message = "Пока нет результатов"
            } catch (e: Exception) { message = e.message ?: "Ошибка подключения" }
            finally { busy = false }
        }
    }
    fun loadHome() = request({ api.home() }, videos)
    fun search(value: String, channels: Boolean = false) {
        query = value.trim()
        if (query.length < 2) { message = "Введи минимум два символа"; return }
        screen = Screen.SEARCH
        request({ api.search(query, channels) }, videos)
    }
    fun loadShorts() {
        screen = Screen.SHORTS
        playerReady = false
        shortsRetries = 0
        viewModelScope.launch { routeMode = route.prepare(config); playerReady = true }
        if (shorts.isEmpty()) request({ api.search("#shorts") }, shorts)
    }
    fun selectScreen(next: Screen) {
        screen = next
        if (next == Screen.HOME && videos.isEmpty()) loadHome()
        if (next == Screen.SHORTS) loadShorts()
    }
    fun openChannel(channelId: String, name: String) {
        if (channelId.isBlank()) return
        selectedChannel = channelId to name
        screen = Screen.CHANNEL
        request({ api.channelVideos(channelId) }, channelVideos)
    }

    fun openVideo(video: Video) {
        if (video.kind == "channel") { openChannel(video.id, video.title); return }
        playing = video
        playerReady = false
        playbackError = ""
        playbackEpoch++
        resumeSeconds = 0
        automaticRetries = 0
        history.removeAll { it.id == video.id }; history.add(0, video)
        library.putVideos("history", history)
        comments.clear()
        viewModelScope.launch {
            try {
                val info = api.videoInfo(video.id)
                if (info == null || !info.embeddable) {
                    playbackError = "YouTube не разрешает встраивание этого видео или видео недоступно"
                    playerReady = true
                    routeMode = RouteMode.UNAVAILABLE
                    return@launch
                }
                routeMode = route.prepare(config)
                if (routeMode == RouteMode.UNAVAILABLE) playbackError = "Не найден доступный маршрут"
            } catch (e: Exception) {
                playbackError = "Не удалось проверить разрешение на встраивание: ${e.message}"
                routeMode = RouteMode.UNAVAILABLE
            }
            playerReady = true
        }
        viewModelScope.launch {
            try { comments.addAll(api.comments(video.id)) }
            catch (_: Exception) { /* comments can be disabled for an individual video */ }
        }
    }
    fun openOriginalYouTube() {
        showOriginalYoutube = true
        youtubeAutoRetries = 0
        originalYoutubeEpoch++
        originalYoutubeReady = false
        originalYoutubeError = ""
        viewModelScope.launch {
            routeMode = route.prepare(config)
            if (routeMode == RouteMode.UNAVAILABLE) {
                originalYoutubeError = "Не удалось настроить подключение. Проверь ссылку и доступность серверов."
            }
            originalYoutubeReady = true
        }
    }
    fun closeOriginalYouTube() { showOriginalYoutube = false; originalYoutubeReady = false }
    fun switchOriginalRoute() {
        youtubeAutoRetries = 0
        nextOriginalRoute()
    }
    /** A WebView main-frame DNS/transport failure can trigger a new node. Media
     * subresource errors are not reliably surfaced by WebView, so we never promise
     * invisible or seamless recovery of an already-open YouTube video. */
    fun reportOriginalMainFrameError() {
        if (!showOriginalYoutube || youtubeAutoRetries >= 2 || originalYoutubeReady.not()) return
        youtubeAutoRetries++
        nextOriginalRoute()
    }
    private fun nextOriginalRoute() {
        route.rotateRelay()
        originalYoutubeReady = false
        viewModelScope.launch {
            routeMode = route.prepare(config, forceRelay = true)
            originalYoutubeEpoch++
            if (routeMode == RouteMode.UNAVAILABLE) originalYoutubeError = "Другие серверы не отвечают — проверь подписку"
            originalYoutubeReady = true
        }
    }

    fun closeVideo() { playing = null; playerReady = false }
    fun retryPlayer(resumeAt: Int = resumeSeconds, automatic: Boolean = false) {
        if (automatic) {
            if (automaticRetries >= 2) return
            automaticRetries++
        }
        resumeSeconds = resumeAt.coerceAtLeast(0)
        if (playing == null) return
        playerReady = false
        playbackError = ""
        viewModelScope.launch {
            if (routeMode == RouteMode.RELAY || routeMode == RouteMode.HAPP) route.rotateRelay()
            routeMode = route.prepare(config, forceRelay = true)
            playbackEpoch++
            playerReady = true
        }
    }
    fun retryShorts() {
        if (shortsRetries >= 2) return
        shortsRetries++
        playerReady = false
        viewModelScope.launch {
            if (routeMode == RouteMode.RELAY || routeMode == RouteMode.HAPP) route.rotateRelay()
            routeMode = route.prepare(config, forceRelay = true)
            playbackEpoch++
            playerReady = true
        }
    }
    fun routeHint(): String = when (routeMode) {
        RouteMode.DIRECT -> "Прямое соединение"
        RouteMode.RELAY -> "Защищённый ретранслятор"
        RouteMode.HAPP -> "Happ-совместимый сервер: ${route.chosenNodeName}"
        RouteMode.UNAVAILABLE -> "Нет доступного маршрута"
    }
    fun saveForLater(video: Video) {
        if (watchLater.any { it.id == video.id }) watchLater.removeAll { it.id == video.id }
        else watchLater.add(0, video)
        library.putVideos("watchLater", watchLater)
    }
    fun toggleSubscription(id: String) {
        if (id.isBlank()) return
        if (subscriptions.contains(id)) subscriptions.remove(id) else subscriptions.add(id)
        library.putSubscriptions(subscriptions.toSet())
    }

    fun likelyShort(video: Video): Boolean = runCatching {
        val d = Duration.parse(video.duration)
        d.seconds in 1..180
    }.getOrDefault(false)

    override fun onCleared() { route.close(); super.onCleared() }
}
