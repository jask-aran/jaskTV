package app.opentv.account

import java.security.SecureRandom
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for account sync. Only standard JCE ciphers, so every Android version (and the tests) agree.
 *
 *  - The account key: 32 random bytes made by the first signed-in device. Every synced item is sealed with it
 *    (AES-256-GCM, the item's kind and id bound in as associated data, so one item can't be swapped for another).
 *  - The recovery code: 20 random letters/digits (100 bits) shown once. It wraps the account key
 *    (PBKDF2-HMAC-SHA256 → AES-GCM); the server keeps only that wrapped copy, the "vault".
 *
 * The server never sees the account key or the recovery code, so it can't read playlists or settings.
 * Losing every signed-in device *and* the recovery code loses the synced data (the account itself survives).
 */
object AccountCrypto {
    private val random = SecureRandom()
    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)
    /** Crockford-style alphabet: no I, L, O, U, so codes read back without confusion. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PBKDF2_ITERATIONS = 210_000

    fun newAccountKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** "7K3M-Q9TB-…" (five groups of four). */
    fun newRecoveryCode(): String = (1..20).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    /** What the person typed, as the code's 20 characters: case, spaces and dashes ignored, O→0 and I/L→1. */
    fun normaliseCode(typed: String): String? = typed.uppercase().filter { !it.isWhitespace() && it != '-' }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
        .takeIf { it.length == 20 && it.all(ALPHABET::contains) }

    /** The vault the server stores: "jv1:<iterations>:<salt>:<nonce>:<sealed key>". */
    fun wrap(accountKey: ByteArray, recoveryCode: String): String {
        val code = requireNotNull(normaliseCode(recoveryCode)) { "Not a recovery code" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val sealed = gcm(Cipher.ENCRYPT_MODE, pbkdf2(code, salt, PBKDF2_ITERATIONS), nonce, "jasktv-vault".toByteArray()).doFinal(accountKey)
        return listOf("jv1", PBKDF2_ITERATIONS.toString(), enc(salt), enc(nonce), enc(sealed)).joinToString(":")
    }

    /** The account key from the vault, or null when the code is wrong. */
    fun unwrap(vault: String, recoveryCode: String): ByteArray? {
        val code = normaliseCode(recoveryCode) ?: return null
        val parts = vault.split(':')
        require(parts.size == 5 && parts[0] == "jv1") { "Unsupported vault" }
        val iterations = parts[1].toInt()
        require(iterations in 100_000..5_000_000) { "Unsupported vault" }
        return runCatching {
            gcm(Cipher.DECRYPT_MODE, pbkdf2(code, dec(parts[2]), iterations), dec(parts[3]), "jasktv-vault".toByteArray())
                .doFinal(dec(parts[4]))
        }.getOrNull()?.takeIf { it.size == 32 }
    }

    /** An item's data as the server stores it: "js1:<nonce><ciphertext>" in base64. */
    // ---- Handing the account key to a TV (QR sign-in) ----
    // The TV makes a one-time P-256 key pair and shows the public half in its QR code. The phone makes its own,
    // agrees a shared secret with the TV's (ECDH), and seals the account key with it. The server carries both
    // public halves and the sealed key, and can open none of it.

    fun pairKeyPair(): java.security.KeyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun publicKeyText(kp: java.security.KeyPair): String = Base64.encodeToString(kp.public.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun pairSecret(mine: java.security.PrivateKey, theirs: String, code: String): ByteArray {
        val pub = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(theirs, Base64.URL_SAFE or Base64.NO_WRAP)))
        val shared = javax.crypto.KeyAgreement.getInstance("ECDH").apply { init(mine); doPhase(pub, true) }.generateSecret()
        return java.security.MessageDigest.getInstance("SHA-256").digest(shared + "jasktv-pair:$code".toByteArray())
    }

    /** The phone's side: (its public key, the account key sealed for the TV). */
    fun sealForTv(accountKey: ByteArray, tvKey: String, code: String): Pair<String, String> {
        val kp = pairKeyPair()
        return publicKeyText(kp) to seal(pairSecret(kp.private, tvKey, code), "pair", code, enc(accountKey))
    }

    /** The TV's side: the account key from the phone. */
    fun openFromPhone(tv: java.security.KeyPair, phoneKey: String, code: String, sealed: String): ByteArray =
        dec(open(pairSecret(tv.private, phoneKey, code), "pair", code, sealed))

    /** Six digits both screens show, so a code typed by hand is checked against the TV it came from. */
    fun pairFingerprint(tvKey: String): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(tvKey.toByteArray())
        val n = ((h[0].toLong() and 0xff) shl 16) or ((h[1].toLong() and 0xff) shl 8) or (h[2].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    fun seal(accountKey: ByteArray, kind: String, id: String, plain: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val sealed = gcm(Cipher.ENCRYPT_MODE, accountKey, nonce, "$kind/$id".toByteArray()).doFinal(plain.toByteArray(Charsets.UTF_8))
        return "js1:" + enc(nonce + sealed)
    }

    fun open(accountKey: ByteArray, kind: String, id: String, sealed: String): String {
        require(sealed.startsWith("js1:")) { "Unsupported item" }
        val bytes = dec(sealed.substring(4))
        require(bytes.size > 28) { "Truncated item" }
        val plain = gcm(Cipher.DECRYPT_MODE, accountKey, bytes.copyOfRange(0, 12), "$kind/$id".toByteArray()).doFinal(bytes, 12, bytes.size - 12)
        return String(plain, Charsets.UTF_8)
    }

    /** Opens an account file sealed by a core (engine `seal_bytes`): nonce ‖ ciphertext ‖ tag, raw, for `kind/id`. */
    fun openBytes(accountKey: ByteArray, kind: String, id: String, sealed: ByteArray): ByteArray {
        require(sealed.size > 28) { "Truncated file" }
        return gcm(Cipher.DECRYPT_MODE, accountKey, sealed.copyOfRange(0, 12), "$kind/$id".toByteArray()).doFinal(sealed, 12, sealed.size - 12)
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }

    /** PBKDF2-HMAC-SHA256 for one 32-byte block, written out: Android only offers it built in from API 26. */
    internal fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    // ---- Email + password ----

    /** What a password gives a device: [loginKey] proves it to the server; [wrapKey] unlocks the account key and never leaves the device. */
    class PasswordKeys(val loginKey: String, val wrapKey: ByteArray)

    const val PASSWORD_ITERATIONS = 300_000

    /** Slow on purpose (PBKDF2, 300k rounds, the email as salt): run off the main thread. */
    fun passwordKeys(email: String, password: String): PasswordKeys {
        val master = pbkdf2(password, "jasktv-account:${email.trim().lowercase()}".toByteArray(), PASSWORD_ITERATIONS)
        fun derive(label: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(master, "HmacSHA256")) }.doFinal(label.toByteArray())
        return PasswordKeys(enc(derive("jasktv-login")), derive("jasktv-wrap"))
    }

    /** The account key sealed with a password's [PasswordKeys.wrapKey]: "jk1:<nonce><sealed>". */
    fun wrapWithKey(accountKey: ByteArray, wrapKey: ByteArray): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        return "jk1:" + enc(nonce + gcm(Cipher.ENCRYPT_MODE, wrapKey, nonce, "jasktv-vault-pw".toByteArray()).doFinal(accountKey))
    }

    fun unwrapWithKey(sealed: String, wrapKey: ByteArray): ByteArray? = runCatching {
        require(sealed.startsWith("jk1:"))
        val bytes = dec(sealed.substring(4))
        gcm(Cipher.DECRYPT_MODE, wrapKey, bytes.copyOfRange(0, 12), "jasktv-vault-pw".toByteArray()).doFinal(bytes, 12, bytes.size - 12)
    }.getOrNull()?.takeIf { it.size == 32 }

    /**
     * The vault the server stores: the account key wrapped by the password (when the account has one) and by the
     * recovery code. Stored as "<password wrap>|<recovery wrap>"; either part may be empty. A bare "jv1:…" is the
     * recovery wrap alone.
     */
    data class Vault(val password: String?, val recovery: String?) {
        fun encode() = (password ?: "") + "|" + (recovery ?: "")
        companion object {
            fun parse(text: String): Vault = if (!text.contains('|')) Vault(null, text)
                else text.split('|', limit = 2).let { Vault(it[0].ifBlank { null }, it[1].ifBlank { null }) }
        }
    }

    /** A stable id for a playlist across devices, from what identifies it (never stored in clear on the server). */
    fun sourceSyncId(accountKey: ByteArray, kind: String, url: String, username: String?, mac: String?): String {
        val h = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(accountKey, "HmacSHA256")) }
            .doFinal("$kind|${url.trim().trimEnd('/').lowercase()}|${username.orEmpty().trim()}|${mac.orEmpty().trim().uppercase()}".toByteArray())
        return Base64.encodeToString(h.copyOf(16), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
