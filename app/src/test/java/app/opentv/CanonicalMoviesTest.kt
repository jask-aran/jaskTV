package app.opentv.catalogue

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.repo.movieFixture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = android.app.Application::class)
class CanonicalMoviesTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenTvDatabase::class.java).build()
    private val dao get() = db.canonicalMovies()
    @Before fun sources() = runTest {
        for (id in 1L..2L) db.sources().insert(app.opentv.data.model.Source(id = id, name = "Provider $id",
            kind = app.opentv.data.model.SourceKind.XTREAM, url = "https://fixture.invalid"))
    }
    @After fun close() = db.close()
    private fun title(id: Int = 27205, name: String = "Inception", year: Int = 2010, language: String? = "en") =
        CanonicalMovie(id, name, name, year, language, null, null, "Synopsis", "Drama", 8.0, 148, 1)
    private suspend fun page(languages: String = "", unknown: Boolean = true, source: Long? = null, query: String = "") : List<CanonicalMovieCard> {
        val result = dao.browse(source, query, languages, unknown, "TITLE")
            .load(PagingSource.LoadParams.Refresh(null, 48, false))
        check(result is PagingSource.LoadResult.Page) { result.toString() }
        return result.data
    }
    private suspend fun link(movie: app.opentv.data.model.Movie, t: CanonicalMovie, manual: Boolean = false) {
        dao.record(t, MovieIdentityMatch(movie.sourceId, movie.streamId, movie.identityEvidence(), t.tmdbId, 0.98, "fixture", manual, ""))
    }

    @Test fun `search order never selects a different remake or ambiguous identity`() {
        val old = MovieCandidate(1, "The Thing", "The Thing", 1982)
        val remake = MovieCandidate(2, "The Thing", "The Thing", 2011)
        assertThat(MovieMatching.decide("The Thing", 1982, listOf(remake, old)))
            .isEqualTo(MovieMatchDecision.Accepted(old, 0.98, "exact-title-year"))
        assertThat(MovieMatching.decide("The Thing", null, listOf(remake, old)))
            .isInstanceOf(MovieMatchDecision.Ambiguous::class.java)
        assertThat(MovieMatching.decide("The Thing", 1982, listOf(remake))).isEqualTo(MovieMatchDecision.Unmatched)
        assertThat(MovieMatching.decide("The Thing", 1982, listOf(old, old.copy(id = 3))))
            .isInstanceOf(MovieMatchDecision.Ambiguous::class.java)
    }

    @Test fun `embedded year is evidence but title digits are not a release year`() {
        assertThat(MovieMatching.releaseYear("TR | Inception (2010) 1080p", null)).isEqualTo(2010)
        assertThat(MovieMatching.releaseYear("2001 A Space Odyssey", null)).isNull()
        assertThat(MovieMatching.explicit(MovieCandidate(1, "The Thing", "The Thing", 2011), 1982))
            .isInstanceOf(MovieMatchDecision.Ambiguous::class.java)
    }

    @Test fun `alternate original title matches only with year evidence`() {
        val candidate = MovieCandidate(1, "Spirited Away", "千と千尋の神隠し", 2001)
        assertThat(MovieMatching.decide("千と千尋の神隠し", 2001, listOf(candidate)))
            .isInstanceOf(MovieMatchDecision.Accepted::class.java)
        assertThat(MovieMatching.decide("Spirited Away", null, listOf(candidate)))
            .isInstanceOf(MovieMatchDecision.Ambiguous::class.java)
    }

    @Test fun `ten provider copies become one card and retain every playable version`() = runTest {
        val movies = (1L..10).map { movieFixture(it, "TR | Inception (2010) 1080p", 2010).copy(sourceId = it % 2 + 1) }
        db.movies().upsertAll(movies)
        movies.forEach { link(it, title()) }
        val card = page().single()
        assertThat(card.versionCount).isEqualTo(10)
        assertThat(dao.versions(card.title.tmdbId)).hasSize(10)
        assertThat(page(source = 1).single().versionCount).isEqualTo(5)
        assertThat(db.movies().byId(1)!!.name).isEqualTo(movies.first().name)
    }

    @Test fun `language filters use original language and retain explicit unknown policy`() = runTest {
        val englishDub = movieFixture(1, "US | Turkish film EN DUB", 2010)
        val unknown = movieFixture(2, "Unknown", 2010)
        db.movies().upsertAll(listOf(englishDub, unknown))
        link(englishDub, title(language = "tr"))
        link(unknown, title(99, "Unknown", language = null))
        assertThat(page("|en|", false)).isEmpty()
        assertThat(page("|tr|", false).map { it.title.tmdbId }).containsExactly(27205)
        assertThat(page("|en|", true).map { it.title.tmdbId }).containsExactly(99)
        assertThat(page(unknown = false)).hasSize(1)
    }

    @Test fun `unavailable identities and stale automatic links do not produce playable cards`() = runTest {
        val movie = movieFixture(1, "Inception", 2010)
        db.movies().upsertAll(listOf(movie)); link(movie, title())
        db.movies().upsertAll(listOf(movie.copy(name = "Different film")))
        assertThat(page()).isEmpty()
        assertThat(dao.pending(10)).hasSize(1)
        assertThat(dao.versions(27205)).isEmpty()
        db.movies().deleteForSource(movie.sourceId)
        assertThat(page()).isEmpty()
        assertThat(dao.title(27205)).isNotNull()
    }

    @Test fun `manual corrections survive source replacement and resist automatic overwrites`() = runTest {
        val movie = movieFixture(1, "Messy name", 2010)
        db.movies().upsertAll(listOf(movie)); link(movie, title(), manual = true)
        db.movies().deleteForSource(movie.sourceId)
        assertThat(page()).isEmpty()
        db.movies().upsertAll(listOf(movie.copy(id = 101, name = "Changed raw name")))
        link(movie, title(99, "Wrong match"))
        assertThat(page().single().title.tmdbId).isEqualTo(27205)
        assertThat(page().single().movieId).isEqualTo(101)
        assertThat(dao.pending(10)).isEmpty()
    }

    @Test fun `low confidence links remain outside the playable catalogue`() = runTest {
        val movie = movieFixture(1, "Inception", 2010)
        db.movies().upsertAll(listOf(movie))
        dao.record(title(), MovieIdentityMatch(movie.sourceId, movie.streamId, movie.identityEvidence(),
            27205, 0.5, "uncertain fixture", false, ""))
        assertThat(page()).isEmpty()
        assertThat(dao.versions(27205)).isEmpty()
    }

    @Test fun `only complete snapshots change availability and raw records remain intact`() = runTest {
        val a = movieFixture(1, "Inception", 2010)
        val b = movieFixture(2, "Other", 2010)
        db.movies().upsertAll(listOf(a,b)); link(a,title(),manual = true); link(b,title(99,"Other"))
        dao.stageInventory(listOf(MovieInventoryAsset(a.sourceId,"first",a.streamId), MovieInventoryAsset(b.sourceId,"first",b.streamId)))
        dao.activateSnapshot(a.sourceId,"first")
        assertThat(page()).hasSize(2)
        dao.stageInventory(listOf(MovieInventoryAsset(b.sourceId,"incomplete",b.streamId)))
        assertThat(page()).hasSize(2) // interrupted refresh is not active
        dao.stageInventory(listOf(MovieInventoryAsset(b.sourceId,"complete",b.streamId)))
        dao.activateSnapshot(a.sourceId,"complete")
        assertThat(page().single().title.tmdbId).isEqualTo(99)
        assertThat(dao.versions(27205)).isEmpty()
        assertThat(db.movies().byId(a.id)).isNotNull()
        assertThat(dao.match(a.sourceId,a.streamId)?.manual).isTrue()
    }

    @Test fun `provider refresh reuses row identity and preserves favourites while replacing raw hints`() = runTest {
        val movie = movieFixture(1, "Old title", 2010).copy(favourite = true, tmdbId = "99")
        db.movies().upsertAll(listOf(movie))
        db.movies().upsertAll(listOf(movie.copy(id = 0, name = "Corrected title", favourite = false, tmdbId = "27205")))
        val refreshed = db.movies().byId(1)!!
        assertThat(refreshed.name).isEqualTo("Corrected title")
        assertThat(refreshed.tmdbId).isEqualTo("27205")
        assertThat(refreshed.favourite).isTrue()
        assertThat(db.movies().count()).isEqualTo(1)
    }

    @Test fun `two remakes are distinct and canonical search is local`() = runTest {
        val a = movieFixture(1, "Provider gibberish", 1982)
        val b = movieFixture(2, "Provider gibberish", 2011)
        db.movies().upsertAll(listOf(a,b))
        link(a, title(1, "The Thing", 1982)); link(b, title(2, "The Thing", 2011))
        assertThat(page(query = "thing").map { it.title.year }).containsExactly(1982,2011)
        assertThat(page(query = "gibberish")).isEmpty()
    }
}
