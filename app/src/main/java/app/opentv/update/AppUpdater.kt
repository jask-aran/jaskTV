package app.opentv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.opentv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates from GitHub Releases, for the release app (phones, tablets and TVs install the same APK). The release
 * workflow publishes `update.json` beside the APK: version, file name and SHA-256. The app checks it at launch (at
 * most every 6 hours) and from Settings, downloads the APK, checks its SHA-256, and hands it to Android's installer,
 * which asks the user to confirm and only accepts an APK signed with the same key as the installed app.
 */
class AppUpdater private constructor(private val context: Context) {
    @Serializable data class Info(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val notes: String = "")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: Info) : State
        data class Downloading(val info: Info, val progress: Float) : State
        data class Ready(val info: Info) : State
        data class Failed(val message: String, val info: Info? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("jask_update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Only the release app updates itself (a debug build is a different app, signed with another key). */
    // Release builds only. A `-PjaskPerf` debug build is not debuggable (DEBUG false) but is a different package
    // (app.jasktv.debug, debug key), so a release APK can never update it; it used to offer one anyway.
    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank() && BuildConfig.BUILD_TYPE == "release" && Build.FINGERPRINT != "robolectric"
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** The version the user said "Later" to (the prompt isn't shown again for it; Settings still offers it). */
    var dismissed: Int
        get() = prefs.getInt("dismissed", 0)
        set(v) { prefs.edit().putInt("dismissed", v).apply() }

    fun checkIfDue() {
        if (!enabled) return
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < 6 * 3_600_000L) return
        check()
    }

    fun check() {
        if (!enabled || _state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                val url = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/update.json"
                val text = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    check(r.isSuccessful) { "GitHub said ${r.code}" }; r.body!!.string()
                }
                json.decodeFromString(Info.serializer(), text)
            }.onSuccess { info ->
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                _state.value = if (info.versionCode > BuildConfig.VERSION_CODE) State.Available(info) else State.UpToDate
            }.onFailure { _state.value = State.Failed("Couldn't check for updates: ${it.message}") }
        }
    }

    /** Downloads, verifies and installs [info]. */
    fun install(info: Info) {
        if (_state.value is State.Downloading) return
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, "jasktv-${info.versionName}.apk")
                val url = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/v${info.versionName}/${info.apk}"
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    check(r.isSuccessful) { "GitHub said ${r.code}" }
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L
                    val digest = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    body.byteStream().use { input -> file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); read += n
                            if (total > 0) _state.value = State.Downloading(info, read.toFloat() / total)
                        }
                    } }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    check(sha.equals(info.sha256, ignoreCase = true)) { "The download doesn't match the release (checksum)" }
                }
                _state.value = State.Ready(info)
                installFile(file)
            }.onFailure { _state.value = State.Failed(it.message ?: "Update failed", info) }
        }
    }

    private fun installFile(file: File) {
        // Android 8+: the user allows jaskTV to install apps once (Settings opens on that switch).
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.value = State.Failed("Allow jaskTV to install updates, then press Install again.", (_state.value as? State.Ready)?.info)
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("jasktv.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallResult::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    /** Android's answer to the install: "please confirm" opens its dialog; a failure is shown in Settings. */
    class InstallResult : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                    Log.w("jaskUpdate", message)
                    get(context)._state.value = State.Failed("Install failed: $message")
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: AppUpdater? = null
        fun get(context: Context): AppUpdater = instance ?: synchronized(this) {
            instance ?: AppUpdater(context.applicationContext).also { instance = it }
        }
    }
}
