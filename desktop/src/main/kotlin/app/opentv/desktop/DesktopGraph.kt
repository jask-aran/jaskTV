/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.opentv.catalogue.TmdbRankings
import app.opentv.core.AppSettings
import app.opentv.core.FilePrefs
import app.opentv.core.desktopHome
import app.opentv.data.db.OpenTvDatabase
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop's dependencies. The database is `opentv.db` in [desktopHome] — for now a copy of the
 * phone's library (accounts and desktop-side sync come later). Settings live in properties files
 * there; on first run they are imported from the phone's SharedPreferences XML if it was copied too.
 */
object DesktopGraph {
    val home: File get() = desktopHome

    val settings: AppSettings by lazy {
        importAndroidPrefs("opentv_settings")
        importAndroidPrefs("jask_catalogue")
        AppSettings.shared()
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    val database: OpenTvDatabase by lazy {
        Room.databaseBuilder<OpenTvDatabase>(name = File(home, "opentv.db").absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    val rankings: TmdbRankings by lazy { TmdbRankings(http, settings, File(home, "rankings")) }

    /** One-time import of an Android `shared_prefs/<name>.xml` into `<name>.properties`. */
    private fun importAndroidPrefs(name: String) {
        val xml = File(home, "$name.xml")
        val props = File(home, "$name.properties")
        if (props.exists() || !xml.exists()) return
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val editor = FilePrefs(props).edit()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val key = e.getAttribute("name")
            when (e.tagName) {
                "string" -> editor.putString(key, e.textContent)
                "boolean" -> editor.putBoolean(key, e.getAttribute("value").toBoolean())
                "int" -> editor.putInt(key, e.getAttribute("value").toInt())
                "long" -> editor.putLong(key, e.getAttribute("value").toLong())
                "set" -> {
                    val items = e.getElementsByTagName("string")
                    editor.putStringSet(key, (0 until items.length).map { items.item(it).textContent }.toSet())
                }
            }
        }
        editor.commit()
    }
}
