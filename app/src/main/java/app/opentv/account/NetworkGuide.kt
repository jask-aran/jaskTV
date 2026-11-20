package app.opentv.account

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

/**
 * The network's TV guide: the free regional guide for the account's channels, built once by the core that builds the
 * account's match table (see the core's guides.rs) and copied by every other core. A device reads it from a core,
 * or from the account's files when no core answers, instead of downloading and matching the big regional files itself.
 * It's an XMLTV feed like any other ("Your jaskTV network" in the guide settings), with the device's own stream names
 * listed on each channel, so the guide matcher pairs them exactly.
 */
object NetworkGuide {
    const val URL = "jasktv:network"
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    /** The guide for one of [sourceKeys] is on the network: a core serves it, or the account has a copy. */
    fun available(sourceKeys: List<String>): Boolean =
        CoreDirectory.endpoint("guide-extra") != null || sourceKeys.any { AccountShared.get("snap", "guide:$it") != null }

    /** The guide for a playlist (gzip XMLTV): from a core, else the account's copy. Null: neither has one. */
    fun open(sourceKey: String, http: OkHttpClient): InputStream? {
        CoreDirectory.endpoint("guide-extra")?.let { core ->
            val response = runCatching {
                http.newCall(Request.Builder().url("${core.base}/guide/$sourceKey").header("Authorization", "Bearer ${core.secret}").build()).execute()
            }.getOrNull()
            if (response != null && response.isSuccessful && response.body != null) return response.body!!.byteStream()
            response?.close()
        }
        val file = AccountShared.get("snap", "guide:$sourceKey")?.get("file")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return null
        val context = app ?: return null
        return AccountManager.get(context).accountFile(file)?.inputStream()
    }
}
