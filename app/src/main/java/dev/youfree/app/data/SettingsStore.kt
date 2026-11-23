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

data class UserSettings(
    val vpnLink: String = "",
    val dpiStrategy: String = "--tlsrec 3+s",
    val apiKey: String = "",
    val dark: Boolean = true
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("youfree_native", Context.MODE_PRIVATE)
    private val alias = "YouFreeNativeSecrets_v1"
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return generator.generateKey()
        }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun unseal(text: String?): String = runCatching {
        if (text.isNullOrBlank()) return ""
        val data = Base64.decode(text, Base64.NO_WRAP)
        require(data.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0,12)))
        String(cipher.doFinal(data.copyOfRange(12,data.size)), Charsets.UTF_8)
    }.getOrDefault("")

    fun load() = UserSettings(
        vpnLink = unseal(prefs.getString("vpn", "")),
        dpiStrategy = prefs.getString("dpi", "--tlsrec 3+s").orEmpty(),
        apiKey = unseal(prefs.getString("api_key", "")),
        dark = prefs.getBoolean("dark", true)
    )

    fun save(value: UserSettings) {
        prefs.edit().putString("vpn", seal(value.vpnLink))
            .putString("dpi", value.dpiStrategy.take(2048))
            .putString("api_key", seal(value.apiKey))
            .putBoolean("dark", value.dark).apply()
    }
}
