package dev.youfree.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The token is for a self-hosted YouFree relay, never a Google account token. */
data class AppConfig(val servers: List<String>, val token: String, val dark: Boolean = true, val happLink: String = "") {
    fun valid(): Boolean = servers.isNotEmpty() && servers.all {
        runCatching { val u = java.net.URI(it); u.scheme == "https" && !u.host.isNullOrBlank() && (u.path.isNullOrBlank() || u.path == "/") }.getOrDefault(false)
    } && token.length >= 24 && token.all { it.code in 33..126 }
}

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("youfree.settings", Context.MODE_PRIVATE)
    private val alias = "YouFreeRelaySecret"
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            return generator.generateKey()
        }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String = runCatching {
        val b = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
    }.getOrDefault("")

    fun load(): AppConfig = AppConfig(
        prefs.getString("servers", "")!!.lineSequence().filter { it.isNotBlank() }.toList(),
        decrypt(prefs.getString("token", "")!!),
        prefs.getBoolean("dark", true),
        decrypt(prefs.getString("happLink", "")!!)
    )
    fun save(c: AppConfig) {
        prefs.edit().putString("servers", c.servers.joinToString("\n"))
            .putString("token", encrypt(c.token)).putBoolean("dark", c.dark)
            .putString("happLink", encrypt(c.happLink)).apply()
    }
}
