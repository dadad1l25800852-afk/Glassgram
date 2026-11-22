package dev.youfree.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.youfree.app.control.ByeByeDpiBridge
import dev.youfree.app.control.OverlayService
import dev.youfree.app.control.YouFreeVpnService
import dev.youfree.app.data.ConfigStore
import dev.youfree.app.happ.HappSubscription
import dev.youfree.app.happ.XrayEmbedded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * YouFree is a companion for the Google-signed YouTube application.
 * Nothing is injected into, repackaged from or impersonates Google's APK.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ControlScreen() }
    }

    // Kept for the optional legacy playback UI compiled in the same module.
    fun enterVideoPip() {
        enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        )
    }

    @Composable
    private fun ControlScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val store = remember { ConfigStore(context) }
        val xray = remember { XrayEmbedded() }
        var link by remember { mutableStateOf(store.load().happLink) }
        var dpiInput by remember { mutableStateOf(store.load().dpiLink) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Вставь VLESS/Trojan/SS/HTTPS-подписку или выбери ByeByeDPI.") }
        var chosen by remember { mutableStateOf<XrayEmbedded.Node?>(null) }
        var vpnActive by remember { mutableStateOf(YouFreeVpnService.running) }

        fun startSelected() {
            val node = chosen ?: return
            val intent = Intent(context, YouFreeVpnService::class.java)
                .setAction(YouFreeVpnService.ACTION_CONNECT)
                .putExtra(YouFreeVpnService.EXTRA_OUTBOUND, node.outbound.toString())
            context.startForegroundService(intent)
            message = "Запуск VPN только для приложения YouTube…"
        }

        val vpnPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) startSelected()
            else message = "Android не разрешил запуск VPN."
        }

        LaunchedEffect(Unit) {
            while (true) {
                vpnActive = YouFreeVpnService.running
                val error = YouFreeVpnService.lastError
                if (error.isNotBlank()) message = error
                delay(1250)
            }
        }

        val youtubePackage = "com.google.android.youtube"
        val installedVersion = remember {
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(youtubePackage, 0).versionName
            }.getOrNull()
        }
        val red = Color(0xFFFF0033)
        val black = Color(0xFF0F0F0F)
        val surface = Color(0xFF202020)
        val scheme = darkColorScheme(
            primary = red, background = black, surface = black,
            surfaceVariant = surface, onBackground = Color.White, onSurface = Color.White
        )

        MaterialTheme(colorScheme = scheme) {
            Surface(color = black, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("▶", color = red, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("YouFree", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text("Панель подключения к YouTube", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ОРИГИНАЛЬНЫЙ YOUTUBE", color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall)
                            Text(
                                if (installedVersion == null) "YouTube не обнаружен"
                                else "Установлен YouTube " + installedVersion,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Последняя замеченная бета: 21.47.483 (23.11.2026). " +
                                "YouFree не изменяет APK Google и не включает его в свою сборку.",
                                color = Color.LightGray, fontSize = 12.sp)
                            Button(
                                onClick = {
                                    val launch = context.packageManager.getLaunchIntentForPackage(youtubePackage)
                                    if (launch != null) context.startActivity(launch)
                                    else context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.youtube"))
                                    )
                                }, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = red)
                            ) { Text("▶  Открыть YouTube", color = Color.White) }
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/apps/testing/com.google.android.youtube")))
                            }) { Text("Программа бета-тестирования YouTube") }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("VPN-подписка", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(Modifier.weight(1f))
                                Text(if (vpnActive) "● Активно" else "○ Выключено",
                                    color = if (vpnActive) Color(0xFF69DB7C) else Color.LightGray)
                            }
                            Text(
                                "Подключение через Xray. Android запросит разрешение VPN. " +
                                "Только трафик приложения YouTube идёт через выбранный сервер.",
                                color = Color.LightGray, fontSize = 12.sp
                            )
                            OutlinedTextField(
                                value = link, onValueChange = { link = it }, label = { Text("Ссылка или подписка") },
                                placeholder = { Text("vless://… или https://…") },
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && link.isNotBlank(),
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    message = "Загружаю подписку и проверяю доступность серверов…"
                                    scope.launch {
                                        try {
                                            val node = withContext(Dispatchers.IO) {
                                                val nodes = xray.decodeOpenSubscription(HappSubscription().import(link))
                                                val ranked = xray.benchmark(nodes)
                                                ranked.firstOrNull { it.delayMs != null }
                                                    ?: error("Ни один сервер не ответил на проверку YouTube")
                                            }
                                            chosen = node
                                            val cfg = store.load()
                                            store.save(cfg.copy(happLink = link.trim(), dark = true))
                                            message = "Выбран: " + node.name + " (" + node.delayMs + " мс)."
                                            val permission = VpnService.prepare(context)
                                            if (permission != null) vpnPermission.launch(permission)
                                            else startSelected()
                                        } catch (e: Exception) {
                                            message = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
                                        } finally { busy = false }
                                    }
                                }
                            ) { Text(if (busy) "Проверка…" else "⚡  Выбрать лучший сервер и подключить") }
                            OutlinedButton(
                                onClick = {
                                    context.startService(
                                        Intent(context, YouFreeVpnService::class.java)
                                            .setAction(YouFreeVpnService.ACTION_STOP)
                                    )
                                    message = "Отключение запрошено."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Отключить VPN") }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("ByeByeDPI", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Режим локального обхода DPI. Открывается установленное " +
                                "приложение ByeByeDPI через его командный интерфейс. " +
                                "Использует Android VPN-режим, но не удалённый VPN-сервер.",
                                color = Color.LightGray, fontSize = 12.sp
                            )
                            OutlinedTextField(
                                value = dpiInput, onValueChange = { dpiInput = it },
                                label = { Text("Ссылка на стратегию или параметры") },
                                placeholder = { Text("https://…/strategy.txt или -s1 …") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2, maxLines = 3
                            )
                            Button(onClick = {
                                if (!ByeByeDpiBridge.installed(context)) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/romanvht/ByeByeDPI")))
                                    message = "Сначала установи ByeByeDPI и один раз разреши ему VPN."
                                } else {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val strategy = ByeByeDpiBridge.resolveStrategy(dpiInput)
                                            store.save(store.load().copy(dpiLink = dpiInput.trim()))
                                            ByeByeDpiBridge.start(context, strategy)
                                            message = "Команда запуска отправлена ByeByeDPI. " +
                                                "Если изменил стратегию при активном подключении, " +
                                                "останови и запусти его заново."
                                        } catch (e: Exception) {
                                            message = "Ошибка ByeByeDPI: " + (e.message ?: "неизвестная")
                                        } finally { busy = false }
                                    }
                                }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("▶  Запустить ByeByeDPI")
                            }
                            OutlinedButton(onClick = {
                                runCatching { ByeByeDpiBridge.stop(context) }
                                    .onSuccess { message = "Отправлена команда остановки ByeByeDPI." }
                                    .onFailure { message = it.message ?: "Не удалось остановить ByeByeDPI" }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Остановить ByeByeDPI") }
                            TextButton(onClick = {
                                val launch = context.packageManager.getLaunchIntentForPackage(
                                    ByeByeDpiBridge.PACKAGE_NAME
                                )
                                if (launch != null) context.startActivity(launch)
                                else context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/romanvht/ByeByeDPI")))
                            }) { Text("Настройки / установить ByeByeDPI") }
                            Text("Одновременно может быть активен только один Android VPN.",
                                color = Color.LightGray, fontSize = 12.sp)
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("КНОПКА ПОВЕРХ YOUTUBE", fontWeight = FontWeight.Bold)
                            Text("Тёмная плавающая кнопка ▶ появляется поверх официального YouTube. " +
                                "Касание открывает эту панель, перетаскивание меняет положение.",
                                color = Color.LightGray, fontSize = 12.sp)
                            Button(onClick = {
                                if (Settings.canDrawOverlays(context)) {
                                    context.startForegroundService(Intent(context, OverlayService::class.java))
                                    message = "Кнопка включена. Открой YouTube."
                                } else {
                                    context.startActivity(Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName)
                                    ))
                                    message = "Разреши отображение поверх других приложений, затем включи кнопку."
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Включить плавающую кнопку") }
                            OutlinedButton(onClick = {
                                context.stopService(Intent(context, OverlayService::class.java))
                                message = "Плавающая кнопка выключена."
                            }, modifier = Modifier.fillMaxWidth()) { Text("Скрыть кнопку") }
                        }
                    }

                    Text(message, color = Color.White, fontSize = 13.sp)
                    Text("Без root. Для VPN требуется подтверждение Android. " +
                        "Ни YouTube, ни ByeByeDPI не принадлежат разработчику YouFree.",
                        color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}
