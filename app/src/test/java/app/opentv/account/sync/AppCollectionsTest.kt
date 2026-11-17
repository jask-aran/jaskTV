package app.opentv.account.sync

import app.opentv.account.AccountPrefs
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.get
import app.opentv.data.model.Channel
import app.opentv.data.model.CollectionEntry
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.UserCollection
import app.opentv.livetv.SavedTvChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The real collections on the app's real database. What matters most: applying a value and reading it back gives
 * the same value (or devices would bounce a change back and forth), and a missing row only counts as a removal
 * when what it names is here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppCollectionsTest {
    @Test fun everyCollectionRoundTripsAndOnlyRealRemovalsCount() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val graph = ServiceLocator.get(app)
        val db = graph.database
        val settings = AppSettings.get(app)
        if (db.profiles().all().none { it.id == 1L }) db.profiles().insertDefault(Profile(id = 1, name = "Me"))
        val source = db.sources().insert(Source(name = "Provider", kind = SourceKind.XTREAM, url = "http://p.example", username = "u", password = "pw"))
        db.channels().upsertAll(listOf(Channel(sourceId = source, streamId = "101", name = "UK| Sky Sports F1", streamUrl = "http://p.example/101", categoryId = null, logoUrl = null, epgChannelId = null, number = null),
            Channel(sourceId = source, streamId = "102", name = "UK| BBC One", streamUrl = "http://p.example/102", categoryId = null, logoUrl = null, epgChannelId = null, number = null)))
        val ch = db.channels().byProviderKey(source, "101")!!
        db.movies().upsertAll(listOf(Movie(sourceId = source, streamId = "m9", name = "Heat", categoryId = null, posterUrl = null, rating = null, year = 1995,
            plot = null, durationSeconds = null, containerExtension = "mkv", streamUrl = "http://p.example/m9")))
        val movie = db.movies().byProviderKey(source, "m9")!!

        // This device's state: a bit of everything.
        db.channels().setFavourite(ch.id, true); db.channels().setCustomName(ch.id, "F1")
        db.channels().setEpgOverride(ch.id, "SkySportsF1.uk")
        db.movies().setFavourite(movie.id, true)
        db.positions().upsert(PlaybackPosition(1, "movie:${movie.id}", 600_000, 6_000_000, 1_700_000_000_000))
        val coll = db.collections().create(UserCollection(name = "Weekend", createdMillis = 5, profileId = 1))
        db.collections().add(CollectionEntry(coll, "movie", source, "m9"))
        db.savedTv().save(SavedTvChannel(1, "skysportsf1", "Sky Sports F1", source, "101", 0))
        settings.setRecentChannels(listOf(ch.id))

        val store = SyncStore(app, "colls-${System.nanoTime()}.db")
        val c = AppCollections(graph, settings, AccountPrefs(app, settings), SyncIds(store), {}, {})
        val before = c.all().associate { it.name to it.snapshot() }
        for (name in listOf("pl", "pr", "cm", "fv", "wp", "co", "ce", "tv", "rc", "gd")) assertTrue("$name has data", before[name]!!.isNotEmpty())
        assertTrue(before["pl"]!!.values.single().toString().contains("\"password\":\"pw\"")) // credentials travel (sealed)

        // Undo everything here, then apply the snapshot back as if from another device: identical afterwards.
        db.channels().setFavourite(ch.id, false); db.channels().setCustomName(ch.id, null); db.channels().setEpgOverride(ch.id, null)
        db.movies().setFavourite(movie.id, false)
        db.positions().delete(1, "movie:${movie.id}")
        db.collections().remove(coll, "movie", source, "m9")
        db.savedTv().remove(1, "skysportsf1")
        settings.setRecentChannels(emptyList())
        for (col in c.all()) for ((k, v) in before[col.name]!!) assertTrue("${col.name}/$k applies", col.apply(k, v))
        val after = c.all().associate { it.name to it.snapshot() }
        assertEquals(before, after)

        // Removals: a favourite turned off on a channel that's here is a removal…
        val chKey = before["cm"]!!.keys.single()
        db.channels().setFavourite(ch.id, false); db.channels().setCustomName(ch.id, null)
        assertTrue(c.channelMarks.removedHere(chKey))
        // …one on a channel this device doesn't have (not loaded, or dropped by the provider) isn't.
        val plUid = before["pl"]!!.keys.single()
        assertFalse(c.channelMarks.removedHere("$plUid|999"))
        assertFalse(c.channelMarks.apply("$plUid|999", before["cm"]!![chKey] as JsonElement)) // waits until it loads
        assertTrue(c.channelMarks.apply("$plUid|999", null)) // a removal of something absent is done
        // A resume point is only removed if the same row it was seen as is gone.
        val wpKey = before["wp"]!!.keys.single()
        assertFalse(c.positions.removedHere(wpKey))
        db.positions().delete(1, "movie:${movie.id}")
        assertTrue(c.positions.removedHere(wpKey))
    }
}
