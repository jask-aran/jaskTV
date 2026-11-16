package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import app.opentv.catalogue.ShowMatching
import app.opentv.data.db.OpenTvDatabase
import app.opentv.ui.vod.cleanEpisodeTitle
import app.opentv.ui.vod.cleanShowTitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import app.opentv.ui.vod.nextEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JaskMediaPageTest {
    @Test fun cleansProviderPrefixes() {
        assertEquals("Symptoms", cleanEpisodeTitle("(2025) (DE) - S01E01 - Symptoms", 1))
        assertEquals("Pilot", cleanEpisodeTitle("Pilot", 1))
        assertEquals("Episode 3", cleanEpisodeTitle("Show - S01E03", 3))
    }

    private fun ep(id: Long, n: Int) = Episode(id = id, sourceId = 1, seriesId = "s", episodeId = "$id", season = 1,
        episodeNumber = n, title = "E$n", plot = null, durationSeconds = null, stillUrl = null, streamUrl = "")
    private fun pos(id: Long, at: Long, done: Boolean) = PlaybackPosition(1, "ep:$id", if (done) 99 else 10, 100, at)

    @Test fun picksResumeThenNextThenFirst() {
        val eps = listOf(ep(1, 1), ep(2, 2), ep(3, 3))
        assertEquals(1L, nextEpisode(eps, emptyList())!!.first.id)
        assertEquals(2L, nextEpisode(eps, listOf(pos(1, 5, true), pos(2, 9, false)))!!.first.id)
        assertEquals(3L, nextEpisode(eps, listOf(pos(2, 9, true)))!!.first.id)
        assertNull(nextEpisode(emptyList(), emptyList()))
    }

    @Test fun cleansShowTitles() {
        assertEquals("Silo", cleanShowTitle("Silo (2023) (US)"))
        assertEquals("12 12 12", cleanShowTitle("12 12 12 (2026)"))
        assertEquals("1923", cleanShowTitle("1923"))
    }

    @Test fun showHintValidation() {
        assertTrue(ShowMatching.accepts(listOf("4K-A+ - Silo (2023) (US)"), 2023, "Silo", "Silo", 2023))
        assertFalse(ShowMatching.accepts(listOf("Berlin ER (2025)"), 2025, "Silo", "Silo", 2023))
        assertTrue(ShowMatching.accepts(listOf("The Office (US) (2005)"), 2005, "The Office", "The Office", 2005))
    }

    /** The hand-written v20→v21 SQL must create exactly the table Room expects. */
    @Test fun migration21MatchesRoomSchema() {
        val schema = java.io.File("../shared/schemas/app.opentv.data.db.OpenTvDatabase/21.json").readText()
        val expected = Regex(""""tableName": "canonical_shows",\s*"createSql": "([^"]+)"""").find(schema)!!.groupValues[1]
            .replace("\\u0060", "`").replace("${'$'}{TABLE_NAME}", "canonical_shows")
        val statements = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") statements += args[0] as String; null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        app.opentv.data.db.OpenTvDatabaseAndroid.MIGRATION_20_21.migrate(db)
        assertEquals(listOf(expected), statements)
    }

    @Test fun migration22AddsNullableColumns() {
        val statements = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") statements += args[0] as String; null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        app.opentv.data.db.OpenTvDatabaseAndroid.MIGRATION_21_22.migrate(db)
        val schema = java.io.File("../shared/schemas/app.opentv.data.db.OpenTvDatabase/22.json").readText()
        val created = Regex(""""tableName": "canonical_shows",\s*"createSql": "([^"]+)"""").find(schema)!!.groupValues[1]
        // Room's v22 table = the v21 table plus exactly these two nullable TEXT columns at the end.
        assertTrue(created.endsWith("`checkedMillis` INTEGER NOT NULL, `network` TEXT, `status` TEXT, PRIMARY KEY(`tmdbId`))"))
        assertEquals(listOf("ALTER TABLE `canonical_shows` ADD COLUMN `network` TEXT", "ALTER TABLE `canonical_shows` ADD COLUMN `status` TEXT"), statements)
    }

    @Test fun migration23AddsNetworkLogo() {
        val statements = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") statements += args[0] as String; null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        app.opentv.data.db.OpenTvDatabaseAndroid.MIGRATION_22_23.migrate(db)
        val schema = java.io.File("../shared/schemas/app.opentv.data.db.OpenTvDatabase/23.json").readText()
        val created = Regex(""""tableName": "canonical_shows",\s*"createSql": "([^"]+)"""").find(schema)!!.groupValues[1]
        assertTrue(created.endsWith("`network` TEXT, `status` TEXT, `networkLogo` TEXT, PRIMARY KEY(`tmdbId`))"))
        assertEquals(listOf("ALTER TABLE `canonical_shows` ADD COLUMN `networkLogo` TEXT"), statements)
    }
}
