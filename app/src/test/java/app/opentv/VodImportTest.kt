package app.opentv.data.repo

import app.opentv.core.get
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class VodImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
    private val source = Source(id = 1, name = "Test", kind = SourceKind.XTREAM,
        url = "https://example.test", username = "test", password = "test")

    @After fun close() { db.close() }

    private fun repository(failMovies: Boolean, failSeriesCategories: Boolean = false): Pair<CatalogRepository, MutableList<String>> {
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val action = chain.request().url.queryParameter("action").orEmpty()
            actions.add(action)
            val fail = (failMovies && action == "get_vod_streams") ||
                (failSeriesCategories && action == "get_series_categories")
            val body = when (action) {
                "get_series" -> """[{"series_id":42,"name":"The Office"}]"""
                "get_vod_streams" -> """[{"stream_id":17,"name":"Film","container_extension":"mp4"}]"""
                else -> "[]"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (fail) 503 else 200).message(if (fail) "Unavailable" else "OK")
                .body(body.toResponseBody()).build()
        }.build()
        val settings = AppSettings.get(context)
        settings.setMoviesEnabled(true)
        settings.setSeriesEnabled(true)
        return CatalogRepository(db.sources(), db.channels(), db.categories(), db.movies(),
            db.series(), db.episodes(), db.positions(), XtreamApi(http), StalkerApi(http),
            http, settings, db) to actions
    }

    @Test fun `movie failure still imports shows and reports incomplete sync`() = runTest {
        val (repo, actions) = repository(failMovies = true)
        assertThat(repo.syncVod(source, 100)).isFalse()
        assertThat(db.series().count()).isEqualTo(1)
        assertThat(db.movies().count()).isEqualTo(0)
        assertThat(actions.indexOf("get_series")).isLessThan(actions.indexOf("get_vod_streams"))
    }

    @Test fun `missing category endpoint does not prevent title import and retry can complete`() = runTest {
        val (partial, _) = repository(failMovies = false, failSeriesCategories = true)
        assertThat(partial.syncVod(source, 100)).isFalse()
        assertThat(db.series().count()).isEqualTo(1)
        assertThat(db.movies().count()).isEqualTo(1)
        val (retry, _) = repository(failMovies = false)
        assertThat(retry.syncVod(source, 200)).isTrue()
        assertThat(db.series().count()).isEqualTo(1)
        assertThat(db.movies().count()).isEqualTo(1)
    }
}
