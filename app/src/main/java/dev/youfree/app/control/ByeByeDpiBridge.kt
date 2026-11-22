package dev.youfree.app.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Uses the exported ToggleActivity in romanvht/ByeByeDPI 1.8.2.
 * This is a consent-based inter-app command, not a bundled ByeDPI engine.
 */
object ByeByeDpiBridge {
    const val PACKAGE_NAME = "io.github.romanvht.byedpi"
    private const val TOGGLE = "io.github.romanvht.byedpi.activities.ToggleActivity"

    fun installed(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) != null

    private fun intent(): Intent =
        Intent().setComponent(ComponentName(PACKAGE_NAME, TOGGLE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(context: Context, command: String?) {
        require(installed(context)) { "Установи ByeByeDPI от romanvht (версия 1.8.2+)." }
        val commandIntent = intent().putExtra("only_start", true)
        if (!command.isNullOrBlank()) commandIntent.putExtra("strategy", command)
        context.startActivity(commandIntent)
    }

    fun stop(context: Context) {
        require(installed(context)) { "ByeByeDPI не установлен" }
        context.startActivity(intent().putExtra("only_stop", true))
    }

    suspend fun resolveStrategy(text: String): String? = withContext(Dispatchers.IO) {
        val value = text.trim()
        if (value.isEmpty()) return@withContext null
        require(value.length <= 4096) { "Ссылка или параметры слишком длинные" }
        val strategy = if (value.startsWith("https://", ignoreCase = true)) {
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null) { "Нужна корректная HTTPS-ссылка" }
            val conn = URL(value).openConnection() as HttpsURLConnection
            conn.connectTimeout = 6500
            conn.readTimeout = 6500
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            try {
                require(conn.responseCode == 200) { "HTTP " + conn.responseCode }
                require(conn.contentLengthLong <= 4096) { "Файл стратегии слишком большой" }
                val bytes = conn.inputStream.use { stream ->
                    val buffer = ByteArray(4097)
                    var count = 0
                    while (count < buffer.size) {
                        val n = stream.read(buffer, count, buffer.size - count)
                        if (n < 0) break
                        count += n
                    }
                    buffer.copyOf(count)
                }
                require(bytes.size <= 4096) { "Файл стратегии слишком большой" }
                String(bytes, Charsets.UTF_8).trim()
            } finally { conn.disconnect() }
        } else value

        require(strategy.startsWith("-")) {
            "Нужны параметры ByeByeDPI (строка, начинающаяся с -)."
        }
        require(strategy.length <= 4096 &&
            !strategy.contains('\u0000') &&
            !strategy.contains('\r') &&
            !strategy.contains('\n')) { "Ожидается одна строка параметров" }
        strategy
    }
}
