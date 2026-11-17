package app.opentv.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This device's account: who is signed in, its session, the account key, and what has been synced
 * (each item's revision and a hash of what it held, so local edits and deletions can be found).
 * The session token and account key are locked by a key in the Android Keystore that never leaves the device.
 */
@Serializable
data class AccountState(
    val server: String,
    val userId: String,
    val email: String,
    val name: String? = null,
    val deviceId: String,
    val tokenSealed: String,
    /** Null until this device has the account key (first setup, or the recovery code entered). */
    val keySealed: String? = null,
    val lastRev: Long = 0,
    val items: Map<String, SyncedItem> = emptyMap(),
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
)

/** What this device last agreed with the server for one item: its revision and a hash of its content (null: deleted). */
@Serializable
data class SyncedItem(val rev: Long, val hash: String?)

/** Seals small secrets for storage on this device. */
interface SecretBox {
    fun seal(plain: ByteArray): String
    fun open(sealed: String): ByteArray
}

/** Android Keystore AES-GCM: the key is made and kept in the device's secure hardware where it has one. */
class KeystoreBox(private val alias: String = "jasktv-account") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return "ks1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    override fun open(sealed: String): ByteArray {
        require(sealed.startsWith("ks1:"))
        val bytes = Base64.decode(sealed.substring(4), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    fun forget() = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
}

/** Where [AccountState] lives: one small JSON file in the app's private storage. */
class AccountStateFile(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "jask-account.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): AccountState? = runCatching { json.decodeFromString<AccountState>(file.readText()) }.getOrNull()

    fun write(state: AccountState?) {
        if (state == null) { file.delete(); return }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AccountState.serializer(), state))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
