/*
 * jaskTV — GPL-3.0-or-later.
 * Optional internet sync preferences. Secrets are encrypted locally using Android Keystore.
 */
package app.opentv.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudSyncConfig(
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val encryptionPhrase: String = "",
    val enabled: Boolean = false,
) {
    val isConfigured: Boolean get() =
        endpoint.startsWith("https://", ignoreCase = true) &&
        username.isNotBlank() && password.isNotBlank() &&
        encryptionPhrase.length >= 12
}

/** A cloud login never travels inside a sync bundle and is never backed up as plaintext. */
class CloudSyncPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jasktv_cloud_sync", Context.MODE_PRIVATE)

    fun load(): CloudSyncConfig = CloudSyncConfig(
        endpoint = prefs.getString("endpoint", "").orEmpty(),
        username = prefs.getString("username", "").orEmpty(),
        password = decrypt(prefs.getString("password", "").orEmpty()),
        encryptionPhrase = decrypt(prefs.getString("phrase", "").orEmpty()),
        enabled = prefs.getBoolean("enabled", false),
    )

    fun save(config: CloudSyncConfig) {
        require(!config.enabled || config.isConfigured) { "Use an HTTPS URL and a 12+ character encryption phrase." }
        prefs.edit()
            .putString("endpoint", config.endpoint.trim().trimEnd('/'))
            .putString("username", config.username.trim())
            .putString("password", encrypt(config.password))
            .putString("phrase", encrypt(config.encryptionPhrase))
            .putBoolean("enabled", config.enabled)
            .apply()
    }

    fun setEnabled(value: Boolean) {
        val existing = load()
        require(!value || existing.isConfigured) { "Configure cloud sync first." }
        prefs.edit().putBoolean("enabled", value).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val current = store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (current != null) return current.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$body"
    }

    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val parts = encoded.split(':', limit = 2)
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore keys are not restored with app backups: the user must reconfigure.
            ""
        }
    }

    private companion object {
        const val KEY_ALIAS = "jasktv_cloud_sync_secrets_v1"
    }
}
