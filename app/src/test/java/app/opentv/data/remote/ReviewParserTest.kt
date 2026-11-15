package app.opentv.data.remote

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ReviewParserTest {
    @Test fun sourceRatingsAreParsedWithoutAssumingEveryServiceExists() {
        val row = JSONObject("""{
            "Response":"True","imdbID":"tt0137523","imdbRating":"8.8",
            "imdbVotes":"2,669,989","Metascore":"67",
            "Ratings":[{"Source":"Rotten Tomatoes","Value":"81%"}]
        }""")
        val score = ReviewParser.parse(row)!!
        assertThat(score.imdbId).isEqualTo("tt0137523")
        assertThat(score.imdb).isWithin(0.001).of(8.8)
        assertThat(score.votes).isEqualTo(2669989L)
        assertThat(score.rottenTomatoes).isEqualTo(81)
        assertThat(score.metacritic).isEqualTo(67)
    }

    @Test fun badOrAbsentRatingsStayNull() {
        val row = JSONObject("""{"Response":"True","imdbRating":"N/A","Metascore":"N/A","Ratings":[]}""")
        val score = ReviewParser.parse(row)!!
        assertThat(score.imdb).isNull()
        assertThat(score.metacritic).isNull()
        assertThat(score.rottenTomatoes).isNull()
        assertThat(ReviewParser.parse(JSONObject("""{"Response":"False"}"""))).isNull()
    }

    @Test fun titleComparisonExcludesSimilarButDifferentWorks() {
        assertThat(ReviewParser.comparable("The Matrix", "The Matrix")).isTrue()
        assertThat(ReviewParser.comparable("The Matrix", "Matrix Reloaded")).isFalse()
    }
}
