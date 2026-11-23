package dev.youfree.app.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Import only *open* Happ-compatible subscriptions. Encrypted happ://crypt* links
 * are vendor-specific and deliberately never 'decrypted' by undocumented means.
 * Pure Kotlin/JVM so it can be compiled and tested without Android.
 */
object HappLinkParser {
    private val schemes = setOf("vless", "vmess", "trojan", "ss", "socks", "hy2", "hysteria2")
    private const val MAX_CHARACTERS = 750_000
    private const val MAX_NODES = 80

    sealed class Input {
        data class Subscription(val url: String) : Input()
        data class Nodes(val text: String) : Input()
    }

    fun classify(input: String): Input {
        val value = input.trim()
        require(value.isNotEmpty()) { "Вставь ссылку на подписку или сервер" }
        require(value.length <= MAX_CHARACTERS) { "Слишком длинная ссылка" }
        if (value.startsWith("happ://crypt", true) || value.startsWith("happ://crypto", true)) {
            throw IllegalArgumentException("Зашифрованная ссылка Happ несовместима с открытым импортом. Попроси у провайдера обычную HTTPS-подписку или VLESS-ссылку.")
        }
        if (value.startsWith("happ://add/", true)) {
            val inner = URLDecoder.decode(value.substringAfter("happ://add/").replace("+", "%2B"), StandardCharsets.UTF_8.name())
            return classify(inner)
        }
        if (value.startsWith("https://", true)) {
            val uri = URI(value)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                "Нужен корректный HTTPS-адрес подписки"
            }
            return Input.Subscription(value)
        }
        if (value.startsWith("http://", true)) error("HTTP небезопасен — нужна HTTPS-подписка")
        return Input.Nodes(cleanNodes(value))
    }

    fun cleanNodes(input: String): String {
        val trimmed = input.trim()
        require(trimmed.length <= MAX_CHARACTERS) { "Слишком большой список серверов" }
        val plaintext = if (trimmed.contains("://")) trimmed else decodeBase64(trimmed) ?: trimmed
        val nodes = plaintext.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .filter { line -> schemes.any { line.startsWith("$it://", true) } }
            .distinct().take(MAX_NODES).toList()
        require(nodes.isNotEmpty()) { "В подписке нет поддерживаемых открытых ссылок VLESS, VMess, Trojan, SS или SOCKS" }
        return nodes.joinToString("\n")
    }

    private fun decodeBase64(value: String): String? = try {
        // Standard and URL-safe Base64 both occur in subscriptions.
        val normalized = value.replace("\\s".toRegex(), "")
        val decoder = if (normalized.contains('-') || normalized.contains('_')) Base64.getUrlDecoder() else Base64.getDecoder()
        String(decoder.decode(normalized), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) { null }
}
