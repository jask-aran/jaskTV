package app.opentv.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The account server's API (server/src/index.ts). Every call but sign-in carries the device's session token. */
interface AccountServer {
    suspend fun signInWithGoogle(idToken: String, deviceName: String, deviceKind: String): SignInResult
    suspend fun register(email: String, loginKey: String, deviceName: String, deviceKind: String): SignInResult
    suspend fun login(email: String, loginKey: String, deviceName: String, deviceKind: String): SignInResult
    suspend fun me(token: String): Me
    suspend fun putVault(token: String, wrapped: String, replace: Boolean): VaultResult
    suspend fun pull(token: String, since: Long): PullResult
    suspend fun push(token: String, items: List<PushItem>): PushResult
    suspend fun signOut(token: String)
    suspend fun removeDevice(token: String, deviceId: String)
    suspend fun deleteAccount(token: String)
}

@Serializable data class AccountUser(val id: String, val email: String, val name: String? = null)
@Serializable data class SignInResult(val token: String, val deviceId: String, val user: AccountUser)
@Serializable data class AccountDevice(val id: String, val name: String, val kind: String, val createdAt: Long, val lastSeen: Long)
@Serializable data class Me(val user: AccountUser? = null, val devices: List<AccountDevice> = emptyList(), val vault: String? = null,
    val rev: Long = 0, val deviceId: String? = null)
@Serializable data class VaultResult(val stored: Boolean, val vault: String)
@Serializable data class RemoteItem(val kind: String, val id: String, val rev: Long, val updatedAt: Long, val deleted: Boolean, val data: String? = null)
@Serializable data class PullResult(val rev: Long, val items: List<RemoteItem>)
@Serializable data class PushItem(val kind: String, val id: String, val baseRev: Long, val updatedAt: Long, val deleted: Boolean, val data: String? = null)
@Serializable data class Accepted(val kind: String, val id: String, val rev: Long)
@Serializable data class PushResult(val rev: Long, val accepted: List<Accepted>, val conflicts: List<RemoteItem>)

/** A server answer that isn't success: [code] is the server's error code ("not_invited", "signed_out"…). */
class AccountError(val status: Int, val code: String, message: String) : Exception(message)

class HttpAccountServer(private val base: String, private val http: OkHttpClient = defaultClient) : AccountServer {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val type = "application/json; charset=utf-8".toMediaType()

    private suspend inline fun <reified T> call(method: String, path: String, token: String?, body: String? = null): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .method(method, body?.toRequestBody(type) ?: if (method == "POST" || method == "PUT") "{}".toRequestBody(type) else null)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val err = runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
                throw AccountError(res.code, err?.error ?: "http_${res.code}", err?.message ?: "Server error ${res.code}")
            }
            json.decodeFromString<T>(text)
        }
    }

    @Serializable private data class ErrorBody(val error: String, val message: String? = null)
    @Serializable private data class SignInBody(val idToken: String, val device: DeviceBody)
    @Serializable data class DeviceBody(val name: String, val kind: String)
    @Serializable private data class PasswordBody(val email: String, val loginKey: String, val device: DeviceBody)
    @Serializable private data class VaultBody(val wrapped: String, val replace: Boolean)
    @Serializable private data class PushBody(val items: List<PushItem>)
    @Serializable private data class Ok(val ok: Boolean = true)

    override suspend fun signInWithGoogle(idToken: String, deviceName: String, deviceKind: String): SignInResult =
        call("POST", "/v1/auth/google", null, json.encodeToString(SignInBody.serializer(), SignInBody(idToken, DeviceBody(deviceName, deviceKind))))
    override suspend fun register(email: String, loginKey: String, deviceName: String, deviceKind: String): SignInResult =
        call("POST", "/v1/auth/register", null, json.encodeToString(PasswordBody.serializer(), PasswordBody(email, loginKey, DeviceBody(deviceName, deviceKind))))
    override suspend fun login(email: String, loginKey: String, deviceName: String, deviceKind: String): SignInResult =
        call("POST", "/v1/auth/login", null, json.encodeToString(PasswordBody.serializer(), PasswordBody(email, loginKey, DeviceBody(deviceName, deviceKind))))
    override suspend fun me(token: String): Me = call("GET", "/v1/me", token)
    override suspend fun putVault(token: String, wrapped: String, replace: Boolean): VaultResult =
        call("PUT", "/v1/vault", token, json.encodeToString(VaultBody.serializer(), VaultBody(wrapped, replace)))
    override suspend fun pull(token: String, since: Long): PullResult = call("GET", "/v1/sync?since=$since", token)
    override suspend fun push(token: String, items: List<PushItem>): PushResult =
        call("POST", "/v1/sync", token, json.encodeToString(PushBody.serializer(), PushBody(items)))
    override suspend fun signOut(token: String) { call<Ok>("POST", "/v1/auth/signout", token) }
    override suspend fun removeDevice(token: String, deviceId: String) { call<Ok>("DELETE", "/v1/devices/$deviceId", token) }
    override suspend fun deleteAccount(token: String) { call<Ok>("DELETE", "/v1/account", token) }

    @Serializable private data class PasswordChangeBody(val loginKey: String, val vault: String)
    /** A new password from a signed-in device: its login key, and the vault with the account key re-wrapped for it. */
    suspend fun changePassword(token: String, loginKey: String, vault: String) {
        call<Ok>("POST", "/v1/auth/password", token, json.encodeToString(PasswordChangeBody.serializer(), PasswordChangeBody(loginKey, vault)))
    }

    // ---- TV sign-in by QR ----
    @Serializable data class PairStart(val code: String, val secret: String, val expiresAt: Long)
    @Serializable data class PairInfo(val tvKey: String, val device: DeviceBody, val expiresAt: Long)
    @Serializable data class PairResult(val token: String, val deviceId: String, val user: AccountUser, val phoneKey: String, val sealedKey: String)
    @Serializable data class PairTake(val state: String, val result: PairResult? = null)
    @Serializable private data class PairStartBody(val tvKey: String, val device: DeviceBody)
    @Serializable private data class SecretBody(val secret: String)
    @Serializable private data class ApproveBody(val phoneKey: String, val sealedKey: String)

    suspend fun pairStart(tvKey: String, name: String, kind: String): PairStart =
        call("POST", "/v1/pair/start", null, json.encodeToString(PairStartBody.serializer(), PairStartBody(tvKey, DeviceBody(name, kind))))
    suspend fun pairTake(code: String, secret: String): PairTake =
        call("POST", "/v1/pair/$code/take", null, json.encodeToString(SecretBody.serializer(), SecretBody(secret)))
    suspend fun pairInfo(token: String, code: String): PairInfo = call("GET", "/v1/pair/$code", token)
    suspend fun pairApprove(token: String, code: String, phoneKey: String, sealedKey: String) {
        call<Ok>("POST", "/v1/pair/$code/approve", token, json.encodeToString(ApproveBody.serializer(), ApproveBody(phoneKey, sealedKey)))
    }

    // ---- Sync v2: records ----
    @Serializable private data class RecordsBody(val records: List<app.opentv.account.sync.WireRecord>)

    /** The account's record store, for the sync engine. */
    fun records(token: String) = object : app.opentv.account.sync.RecordTransport {
        override suspend fun put(records: List<app.opentv.account.sync.WireRecord>): app.opentv.account.sync.PutResult =
            call("POST", "/v2/records", token, json.encodeToString(RecordsBody.serializer(), RecordsBody(records)))
        override suspend fun pull(since: Long, limit: Int): app.opentv.account.sync.PullPage = call("GET", "/v2/records?since=$since&limit=$limit", token)
    }

    /** The live connection: the server says "changed" when another device synced. Kept alive with "ping". */
    fun live(token: String, listener: WebSocketListener): WebSocket = liveClient.newWebSocket(
        Request.Builder().url(base.trimEnd('/').replaceFirst("http", "ws") + "/v1/live").header("Authorization", "Bearer $token").build(),
        listener)

    // A live connection stays open: no overall time limit, and no read timeout (the app sends "ping" itself).
    private val liveClient by lazy { http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build() }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}
