package app.opentv.catalogue

import app.opentv.core.get
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.repo.movieFixture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CanonicalResolverTest {
    private val settings = AppSettings.get(ApplicationProvider.getApplicationContext<Context>()).apply { setTmdbApiKey("fixture-key") }
    private fun client(code: Int = 200, response: (String) -> String) = OkHttpClient.Builder().addInterceptor {
        val path = it.request().url.encodedPath
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(response(path).toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun `valid provider TMDB id resolves typed movie metadata without title search`() = runTest {
        val paths = mutableListOf<String>()
        val resolver = CanonicalMovieResolver(client { path -> paths += path
            """{"id":27205,"title":"Inception","original_title":"Inception","release_date":"2010-07-16","original_language":"en","runtime":148,"vote_average":8.3,"genres":[{"name":"Science Fiction"}]}"""
        }, settings)
        val (title, decision) = resolver.resolve(movieFixture(1,"Bad provider name").copy(tmdbId="27205"))
        assertThat(paths).containsExactly("/3/movie/27205")
        assertThat(title!!.originalLanguage).isEqualTo("en")
        assertThat(title.runtimeMinutes).isEqualTo(148)
        assertThat(decision).isInstanceOf(MovieMatchDecision.Accepted::class.java)
    }

    @Test fun `first result with wrong year is never fetched as the matched detail`() = runTest {
        val paths = mutableListOf<String>()
        val resolver = CanonicalMovieResolver(client { path -> paths += path
            """{"results":[{"id":2,"title":"The Thing","original_title":"The Thing","release_date":"2011-01-01"}]}"""
        }, settings)
        val (title, decision) = resolver.resolve(movieFixture(1,"The Thing",1982))
        assertThat(title).isNull()
        assertThat(decision).isEqualTo(MovieMatchDecision.Unmatched)
        assertThat(paths).containsExactly("/3/search/movie")
    }

    @Test fun `rate limits propagate as retryable errors rather than unmatched decisions`() = runTest {
        val resolver = CanonicalMovieResolver(client(429) { "{}" }, settings)
        var failure: IOException? = null
        try { resolver.resolve(movieFixture(1,"Inception",2010)) } catch (e: IOException) { failure = e }
        assertThat(failure?.message).isEqualTo("Metadata service HTTP 429")
    }

    @Test fun `wrong id returned by detail endpoint is rejected`() = runTest {
        val resolver = CanonicalMovieResolver(client { """{"id":99,"title":"Wrong movie"}""" }, settings)
        val (title, decision) = resolver.resolve(movieFixture(1,"Inception",2010).copy(tmdbId="27205"))
        assertThat(title).isNull()
        assertThat(decision).isEqualTo(MovieMatchDecision.Unmatched)
    }
}
