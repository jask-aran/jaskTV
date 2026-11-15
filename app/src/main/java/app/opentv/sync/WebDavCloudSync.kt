/*
 * jaskTV — GPL-3.0-or-later.
 * Serverless-to-us internet rendezvous using a user's existing HTTPS WebDAV storage.
 * Nothing runs persistently on any Android device: every client publishes a small encrypted file.
 */
package app.opentv.sync

import android.content.Context
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class WebDavCloudSync(private val context: Context, private val graph: ServiceLocator.Graph) {
    sealed interface Result {
        data class Success(val merged: Int, val peers: Int) : Result
        data object NotConfigured : Result
        data class Failed(val message: String) : Result
    }

    private val engine = SyncEngine(graph)
    private val maxBytes = 3L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val config = CloudSyncPrefs(context).load()
        if (!config.enabled || !config.isConfigured) return@withContext Result.NotConfigured
        try {
            val userFolder = config.endpoint.toHttpUrl().newBuilder()
                .addPathSegment("jasktv-sync").addPathSegment("").build()
            require(userFolder.isHttps) { "Cloud sync requires HTTPS." }
            val auth = Credentials.basic(config.username, config.password)
            createFolder(userFolder, auth)
            val self = graph.settings.syncDeviceId + ".json.enc"
            val peerNames = enumerate(userFolder, auth).filter { it != self }.take(40)

            var merged = SyncEngine.MergeResult()
            var peers = 0
            for (file in peerNames) {
                val encrypted = read(child(userFolder, file), auth) ?: continue
                // Wrong phrase or corrupted encrypted state must be surfaced, not silently ignored.
                val peer = engine.decode(CloudCipher.open(encrypted, config.encryptionPhrase))
                merged += engine.apply(peer)
                peers++
            }
            // Upload AFTER reading peers so the next device receives this device's merged state.
            val updated = CloudCipher.seal(engine.encode(engine.gather()), config.encryptionPhrase)
            write(child(userFolder, self), auth, updated)
            Result.Success(merged.total, peers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(e.message?.take(180) ?: "Cloud sync failed. Verify URL, credentials and encryption phrase.")
        }
    }

    private fun child(folder: HttpUrl, file: String): HttpUrl =
        folder.newBuilder().addPathSegment(file).build()

    private fun createFolder(folder: HttpUrl, auth: String) {
        val request = request(folder, auth).method("MKCOL", null).build()
        client.newCall(request).execute().use { response ->
            // Existing collections commonly return 405.
            require(response.code == 201 || response.code == 405) {
                "Cannot create cloud sync folder (HTTP ${response.code})"
            }
        }
    }

    private fun enumerate(folder: HttpUrl, auth: String): List<String> {
        val request = request(folder, auth).method("PROPFIND", null)
            .header("Depth", "1").build()
        val xml = client.newCall(request).execute().use { response ->
            require(response.code == 207) { "Cannot list sync snapshots (HTTP ${response.code})" }
            response.body?.byteStream()?.use { readLimited(it) } ?: ""
        }
        // Only accept safe, generated file names. Never trust arbitrary href paths from a server.
        val names = Regex("<(?:[A-Za-z0-9]+:)?href\\b[^>]*>([^<]+)</(?:[A-Za-z0-9]+:)?href>",
            RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { match ->
            match.groupValues[1].substringBefore('?').substringAfterLast('/')
                .takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,80}\\.json\\.enc")) }
        }.distinct().toList()
        return names
    }

    private fun read(url: HttpUrl, auth: String): String? {
        val request = request(url, auth).get().build()
        return client.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> null
                200 -> response.body?.byteStream()?.use { readLimited(it) }
                else -> error("Cannot read sync snapshot (HTTP ${response.code})")
            }
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            size += count
            require(size <= maxBytes) { "Remote snapshot exceeds the allowed size" }
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }

    private fun write(url: HttpUrl, auth: String, encrypted: String) {
        val body = encrypted.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val request = request(url, auth).put(body).build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Cannot upload sync snapshot (HTTP ${response.code})" }
        }
    }

    private fun request(url: HttpUrl, auth: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", auth)
            .header("User-Agent", "jaskTV-sync/1")
}
