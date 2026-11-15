/*
 * jaskTV — GPL-3.0-or-later.
 * Encrypted, portable sync snapshots. Only devices with the shared passphrase can read them.
 */
package app.opentv.sync

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object CloudCipher {
    private const val ITERATIONS = 180_000
    private const val MAX_PLAINTEXT = 2 * 1024 * 1024

    fun seal(plain: String, phrase: String): String {
        require(phrase.length >= 12)
        require(plain.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT)
        val rng = SecureRandom()
        val salt = ByteArray(16).also(rng::nextBytes)
        val nonce = ByteArray(12).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(phrase, salt), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf("jasktv", "v1", base64(salt), base64(nonce), base64(encrypted))
            .joinToString(":")
    }

    fun open(envelope: String, phrase: String): String {
        require(envelope.length <= MAX_PLAINTEXT * 2)
        val parts = envelope.split(':', limit = 5)
        require(parts.size == 5 && parts[0] == "jasktv" && parts[1] == "v1") {
            "Unsupported encrypted sync snapshot"
        }
        val salt = unbase64(parts[2])
        val nonce = unbase64(parts[3])
        require(salt.size == 16 && nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(phrase, salt), GCMParameterSpec(128, nonce))
        val bytes = cipher.doFinal(unbase64(parts[4]))
        require(bytes.size <= MAX_PLAINTEXT)
        return String(bytes, Charsets.UTF_8)
    }

    private fun derive(phrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(phrase.toCharArray(), salt, ITERATIONS, 256)
        try {
            val algorithm = if (android.os.Build.VERSION.SDK_INT >= 26)
                "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
            val bytes = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun base64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun unbase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}
