package dev.youfree.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.Headers
import dev.youfree.app.MainActivity
import dev.youfree.app.Screen
import dev.youfree.app.YouFreeViewModel
import dev.youfree.app.data.Video
import dev.youfree.app.network.RouteMode
import dev.youfree.app.player.YouTubeEmbeddedPlayer
import dev.youfree.app.player.OriginalYouTubePage

private val accent = Color(0xFFFF2946)
private val darkBackground = Color(0xFF101014)
private val darkSurface = Color(0xFF1D1D24)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouFreeRoot(vm: YouFreeViewModel) {
    val dark = vm.themeDark
    val palette = if (dark) darkColorScheme(primary=accent, background=darkBackground,
        surface=darkBackground, surfaceVariant=darkSurface, onBackground=Color.White,
        onSurface=Color.White, onSurfaceVariant=Color(0xFFB9B9C1))
    else lightColorScheme(primary=accent, background=Color.White, surface=Color.White,
        surfaceVariant=Color(0xFFF0F0F3))
    val context = LocalContext.current
    var searchVisible by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf("") }
    var channelSearch by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = palette) {
        val selectedVideo = vm.playing
        BackHandler(enabled=selectedVideo != null || vm.showOriginalYoutube || vm.screen == Screen.CHANNEL || searchVisible) {
            when {
                selectedVideo != null -> vm.closeVideo()
                vm.showOriginalYoutube -> vm.closeOriginalYouTube()
                vm.screen == Screen.CHANNEL -> vm.selectScreen(Screen.HOME)
                else -> searchVisible=false
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (selectedVideo == null && !vm.showMediaTest && !vm.showOriginalYoutube) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal=16.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayCircleFilled, null, tint=accent, modifier=Modifier.size(33.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("YouFree", fontSize=23.sp, fontWeight=FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick={ searchVisible = !searchVisible }) {
                                Icon(Icons.Default.Search, "Поиск")
                            }
                            IconButton(onClick={ vm.openOriginalYouTube() }) {
                                Icon(Icons.Default.Language, "Обычный YouTube")
                            }
                            IconButton(onClick={ vm.showSettings=true }) {
                                Icon(Icons.Default.Settings, "Настройки")
                            }
                        }
                        if (searchVisible) {
                            Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=10.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value=searchTerm, onValueChange={searchTerm=it}, singleLine=true,
                                    placeholder={Text("Найти видео или канал")},
                                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                                    keyboardActions=KeyboardActions(onSearch={vm.search(searchTerm,channelSearch)}),
                                    modifier=Modifier.weight(1f), shape=RoundedCornerShape(16.dp)
                                )
                                IconButton(onClick={vm.search(searchTerm,channelSearch)}) {
                                    Icon(Icons.Default.ArrowForward, "Искать")
                                }
                            }
                            Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                                FilterChip(selected=!channelSearch, onClick={channelSearch=false}, label={Text("Видео")})
                                Spacer(Modifier.width(8.dp))
                                FilterChip(selected=channelSearch,onClick={channelSearch=true},label={Text("Каналы")})
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (selectedVideo == null && !vm.showMediaTest && !vm.showOriginalYoutube) {
                    NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation=2.dp) {
                        NavigationBarItem(selected=vm.screen==Screen.HOME,
                            onClick={vm.selectScreen(Screen.HOME)},icon={Icon(Icons.Default.Home,null)},label={Text("Главная")})
                        NavigationBarItem(selected=vm.screen==Screen.SHORTS,
                            onClick={vm.loadShorts()},icon={Icon(Icons.Default.SmartDisplay,null)},label={Text("Shorts")})
                        NavigationBarItem(selected=vm.screen==Screen.SUBSCRIPTIONS,
                            onClick={vm.selectScreen(Screen.SUBSCRIPTIONS)},icon={Icon(Icons.Default.Subscriptions,null)},label={Text("Подписки")})
                        NavigationBarItem(selected=vm.screen==Screen.LIBRARY,
                            onClick={vm.selectScreen(Screen.LIBRARY)},icon={Icon(Icons.Default.VideoLibrary,null)},label={Text("Вы")})
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    vm.showOriginalYoutube -> OriginalYouTubePage(vm)
                    vm.showMediaTest -> MediaTestScreen(vm,onBack={vm.showMediaTest=false})
                    selectedVideo != null -> VideoScreen(vm,selectedVideo)
                    vm.screen == Screen.HOME || vm.screen == Screen.SEARCH ->
                        FeedScreen(vm,vm.videos, vm.screen == Screen.HOME)
                    vm.screen == Screen.SHORTS -> ShortsScreen(vm)
                    vm.screen == Screen.SUBSCRIPTIONS -> SubscriptionsScreen(vm)
                    vm.screen == Screen.LIBRARY -> LibraryScreen(vm)
                    vm.screen == Screen.CHANNEL -> ChannelScreen(vm)
                }
                if (vm.busy && selectedVideo == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
        if (vm.showSettings) SettingsDialog(vm)
    }
}

@Composable
private fun FeedScreen(vm:YouFreeViewModel, list:List<Video>, home:Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(bottom=16.dp)) {
        if(home) item {
            TextButton(onClick={vm::openOriginalYouTube},modifier=Modifier.padding(horizontal=12.dp)) {
                Icon(Icons.Default.Language,null); Spacer(Modifier.width(7.dp))
                Text("Открыть обычный YouTube — без своего API-сервера")
            }
            Row(Modifier.padding(horizontal=16.dp,vertical=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("В тренде","Музыка","Игры","Технологии").forEach { term ->
                    SuggestionChip(onClick={vm.search(term)}, label={Text(term)})
                }
            }
        }
        if (vm.message.isNotBlank()) item {Notice(vm.message,{vm.showSettings=true})}
        if (list.isEmpty() && !vm.busy) item {
            EmptyState("Здесь появятся реальные видео из YouTube Data API", "Подключить сервер") { vm.showSettings=true }
        }
        items(list,key={it.id}) { video -> VideoCard(vm,video, {vm.openVideo(video)}) }
    }
}

@Composable
private fun VideoCard(vm:YouFreeViewModel, video: Video, onClick:()->Unit) {
    val context = LocalContext.current
    val thumbnail = vm.api.thumbnailURL(video.thumbnail)
    val imageRequest = remember(video.thumbnail,thumbnail,vm.config.token) {
        if(thumbnail==null) video.thumbnail else ImageRequest.Builder(context).data(thumbnail)
            .headers(Headers.Builder().add("Authorization", "Bearer ${vm.config.token}").build()).build()
    }
    Column(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(bottom=14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (video.thumbnail.isNotBlank()) AsyncImage(model=imageRequest,contentDescription=video.title,
                contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
            if (video.duration.isNotBlank()) {
                Text(video.duration.removePrefix("PT"),color=Color.White,fontSize=11.sp,
                    modifier=Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha=.8f),RoundedCornerShape(4.dp)).padding(4.dp))
            }
        }
        Row(Modifier.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.Top) {
            Box(Modifier.size(39.dp).clip(CircleShape).background(accent.copy(alpha=.23f)),contentAlignment=Alignment.Center) {
                Text(video.channel.take(1).uppercase(),fontWeight=FontWeight.Bold,color=accent)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, maxLines=2, overflow=TextOverflow.Ellipsis,
                    fontWeight=FontWeight.SemiBold,fontSize=15.sp,lineHeight=20.sp)
                Text(video.channel + if(video.viewCount.isNotEmpty()) " · ${video.viewCount} просмотров" else "",
                    maxLines=1, overflow=TextOverflow.Ellipsis,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=12.sp)
            }
            Icon(Icons.Default.MoreVert,null, tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VideoScreen(vm:YouFreeViewModel, video:Video) {
    val ctx=LocalContext.current
    val ready=vm.playerReady
    val epoch=vm.playbackEpoch
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=vm::closeVideo) {Icon(Icons.Default.ArrowBack,"Назад")}
                Text("Сейчас смотрят",fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
                IconButton(onClick={ (ctx as? MainActivity)?.enterVideoPip() }) {
                    Icon(Icons.Default.PictureInPictureAlt,"Картинка в картинке")
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(Color.Black)) {
                if (ready && vm.routeMode != RouteMode.UNAVAILABLE) {
                    key(video.id,epoch) {
                        YouTubeEmbeddedPlayer(video.id,Modifier.fillMaxSize(),startSeconds=vm.resumeSeconds,onFailure={ reason ->
                            if (reason.startsWith("stall:")) vm.retryPlayer(reason.substringAfter(":").toIntOrNull() ?: 0, automatic=true)
                        })
                    }
                } else {
                    if (ready) Text(vm.playbackError.ifBlank { "Нет рабочего маршрута. Проверь серверы в настройках." },Modifier.align(Alignment.Center).padding(16.dp),color=Color.White)
                    else CircularProgressIndicator(Modifier.align(Alignment.Center),color=accent)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi,null,tint=if(vm.routeMode==RouteMode.UNAVAILABLE) accent else Color(0xFF56A973))
                Spacer(Modifier.width(8.dp))
                Text(vm.routeHint(),fontSize=12.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={vm.retryPlayer()}){Icon(Icons.Default.Refresh,null,Modifier.size(17.dp)); Text("Сменить маршрут")}
            }
            Text(video.title,Modifier.padding(horizontal=16.dp,vertical=8.dp),
                fontSize=21.sp,fontWeight=FontWeight.Bold,lineHeight=26.sp)
            Text("${video.viewCount} просмотров",Modifier.padding(horizontal=16.dp),fontSize=12.sp,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha=.2f)),contentAlignment=Alignment.Center) {
                    Text(video.channel.take(1).uppercase(),color=accent,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.weight(1f).clickable {vm.openChannel(video.channelId,video.channel)}.padding(horizontal=12.dp)) {
                    Text(video.channel,maxLines=1,fontWeight=FontWeight.SemiBold)
                    Text("Перейти на канал",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick={vm.toggleSubscription(video.channelId)},shape=RoundedCornerShape(24.dp)) {
                    Text(if(vm.subscriptions.contains(video.channelId))"Вы подписаны" else "Подписаться")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                SmallAction(Icons.Default.BookmarkAdd,if(vm.watchLater.any{it.id==video.id})"Сохранено" else "Позже") { vm.saveForLater(video) }
                SmallAction(Icons.Default.Share,"Поделиться") {
                    val intent=Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT,"https://www.youtube.com/watch?v=${video.id}")
                    ctx.startActivity(Intent.createChooser(intent,"Поделиться"))
                }
                SmallAction(Icons.Default.OpenInNew,"YouTube") {
                    val url=android.net.Uri.parse("https://www.youtube.com/watch?v=${video.id}")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW,url))
                }
            }
            Spacer(Modifier.height(16.dp))
            if(video.description.isNotBlank()) Text(video.description.take(600),Modifier.padding(horizontal=16.dp),fontSize=13.sp)
            Spacer(Modifier.height(20.dp))
            Text("Комментарии · ${vm.comments.size}",Modifier.padding(horizontal=16.dp),fontWeight=FontWeight.Bold,fontSize=18.sp)
        }
        items(vm.comments.take(30)) { c ->
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
                Text(c.author,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(c.text,fontSize=14.sp)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun SmallAction(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,onClick:()->Unit) {
    FilledTonalButton(onClick=onClick,contentPadding=PaddingValues(horizontal=12.dp,vertical=7.dp)) {
        Icon(icon,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(title,fontSize=12.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortsScreen(vm: YouFreeViewModel) {
    if(vm.shorts.isEmpty()) {
        EmptyState(if(vm.busy)"Ищу короткие видео..." else "Пока нет коротких видео","Найти Shorts") {vm.loadShorts()}
        return
    }
    val pager = rememberPagerState(pageCount={vm.shorts.size})
    VerticalPager(state=pager,modifier=Modifier.fillMaxSize()) { index ->
        val video = vm.shorts[index]
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f)) {
                // The embedded YouTube player retains its native controls and branding.
                if (vm.playerReady && vm.routeMode != RouteMode.UNAVAILABLE)
                    key(vm.playbackEpoch,video.id) {
                        YouTubeEmbeddedPlayer(video.id,Modifier.fillMaxSize(),onFailure={ reason ->
                            if (reason.startsWith("stall:")) vm.retryShorts()
                        })
                    }
                else Text("Нет доступного маршрута",Modifier.align(Alignment.Center),color=Color.White)
            }
            Row(Modifier.fillMaxWidth().background(Color.Black).padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(video.channel,color=Color.White,fontWeight=FontWeight.Bold)
                    Text(video.title,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                IconButton(onClick={vm.openVideo(video)}) {Icon(Icons.Default.OpenInFull,null,tint=Color.White)}
            }
        }
    }
}

@Composable
private fun SubscriptionsScreen(vm:YouFreeViewModel) {
    if(vm.subscriptions.isEmpty()) {
        EmptyState("Подписки сохраняются на этом устройстве", "На главную") {vm.selectScreen(Screen.HOME)}
        return
    }
    val followedVideos=vm.videos.filter { it.channelId in vm.subscriptions }
    LazyColumn {
        item {Text("Ваши локальные подписки: ${vm.subscriptions.size}",Modifier.padding(16.dp),fontWeight=FontWeight.Bold)}
        items(vm.subscriptions) {id ->
            ListItem(headlineContent={Text(vm.history.firstOrNull{it.channelId==id}?.channel ?: id)},
                supportingContent={Text("Видео канала")}, modifier=Modifier.clickable {
                    vm.openChannel(id,vm.history.firstOrNull{it.channelId==id}?.channel ?: id)
                },trailingContent={Icon(Icons.Default.ChevronRight,null)})
        }
        items(followedVideos){video->VideoCard(vm,video){vm.openVideo(video)}}
    }
}

@Composable
private fun LibraryScreen(vm:YouFreeViewModel) {
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Text("Ваша библиотека",Modifier.padding(16.dp),fontWeight=FontWeight.Bold,fontSize=22.sp)
        TabRow(selectedTabIndex=selected) {
            Tab(selected=selected==0,onClick={selected=0},text={Text("История")})
            Tab(selected=selected==1,onClick={selected=1},text={Text("Смотреть позже")})
        }
        val list=if(selected==0)vm.history else vm.watchLater
        if(list.isEmpty()) EmptyState("Видео пока не добавлены", "На главную") {vm.selectScreen(Screen.HOME)}
        else LazyColumn { items(list,key={it.id}){v-> VideoCard(vm,v){vm.openVideo(v)} } }
    }
}

@Composable
private fun ChannelScreen(vm:YouFreeViewModel) {
    val selected=vm.selectedChannel ?: return
    LazyColumn {
        item {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={vm.selectScreen(Screen.HOME)}){Icon(Icons.Default.ArrowBack,null)}
                Column(Modifier.weight(1f)) {
                    Text(selected.second,fontSize=20.sp,fontWeight=FontWeight.Bold)
                    Text("Видео канала",fontSize=12.sp)
                }
                Button(onClick={vm.toggleSubscription(selected.first)}) {
                    Text(if(vm.subscriptions.contains(selected.first)) "Отписаться" else "Подписаться")
                }
            }
        }
        if (vm.message.isNotBlank()) item {Notice(vm.message,{vm.showSettings=true})}
        items(vm.channelVideos,key={it.id}) {v->VideoCard(vm,v){vm.openVideo(v)} }
    }
}

@Composable
private fun Notice(text:String,onOpen:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp)) {
        Text(text,fontSize=13.sp)
        TextButton(onClick=onOpen){ Text("Настройки подключения") }
    }
}

@Composable
private fun EmptyState(message:String,button:String,onClick:()->Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,
        horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Default.OndemandVideo,null,Modifier.size(66.dp),tint=accent)
        Spacer(Modifier.height(16.dp))
        Text(message,fontSize=16.sp,lineHeight=24.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick=onClick) {Text(button)}
    }
}

@Composable
private fun SettingsDialog(vm:YouFreeViewModel) {
    var servers by remember { mutableStateOf(vm.config.servers.joinToString("\n")) }
    var secret by remember { mutableStateOf(vm.config.token) }
    var happ by remember { mutableStateOf(vm.config.happLink) }
    AlertDialog(onDismissRequest={vm.showSettings=false},
        title={Text("Подключение YouFree")},
        text={
            Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState())) {
                Text("Подключение Happ (совместимые открытые подписки)",fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Вставь HTTPS-ссылку подписки или отдельную ссылку VLESS / VMess / Trojan / Shadowsocks / SOCKS. YouFree проверит YouTube через серверы и выберет самый быстрый из доступных.",fontSize=12.sp)
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(value=happ,onValueChange={happ=it},label={Text("Ссылка Happ / VLESS")},
                    placeholder={Text("Вставь ссылку сюда")},maxLines=3,modifier=Modifier.fillMaxWidth())
                Button(onClick={vm.setHappLink(happ)},enabled=!vm.happBusy,modifier=Modifier.fillMaxWidth()) {
                    if(vm.happBusy) CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=Color.White)
                    else Icon(Icons.Default.Wifi,null,Modifier.size(17.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(if(vm.happBusy) "Проверяю…" else "Импортировать и выбрать сервер")
                }
                if(vm.happStatus.isNotBlank()) Text(vm.happStatus,fontSize=12.sp,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                vm.happNodes.take(10).forEachIndexed { index, node ->
                    Row(Modifier.fillMaxWidth().padding(vertical=2.dp)) {
                        Text(if(index==0) "★ " else "• ",color=accent)
                        Text(node.first,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
                        Text(node.second?.let { "$it мс" } ?: "недоступен",fontSize=12.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Необязательно: старый YouFree Backend",fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("Нужно только для собственной ленты и поиска через YouTube Data API. Обычная страница YouTube работает без этого сервера.",fontSize=12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value=servers,onValueChange={servers=it},label={Text("https://your-domain")},
                    minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=secret,onValueChange={secret=it},label={Text("Секрет YouFree-сервера")},
                    visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Токен хранится зашифрованным Android Keystore. Google-пароли не нужны.",fontSize=12.sp)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Тёмная тема",modifier=Modifier.weight(1f))
                    Switch(checked=vm.themeDark,onCheckedChange={vm.toggleTheme()})
                }
                OutlinedButton(onClick={vm.showMediaTest=true;vm.showSettings=false}) {
                    Text("Проверить Media3 на своём HLS-потоке")
                }
                if(vm.message.isNotBlank()) Text(vm.message,color=accent,fontSize=12.sp)
            }
        },
        confirmButton={TextButton(onClick={
            if(servers.isBlank() && secret.isBlank()) vm.showSettings=false
            else vm.saveConfiguration(servers,secret)
        }) {Text("Готово")}},
        dismissButton={TextButton(onClick={vm.showSettings=false}) {Text("Закрыть")}})
}
