package app.opentv.catalogue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Embedded
import app.opentv.data.model.Movie

@Entity(tableName = "canonical_movies", indices = [Index("title"), Index("originalLanguage")])
data class CanonicalMovie(
    @PrimaryKey val tmdbId: Int,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    val originalTitle: String,
    val year: Int?,
    val originalLanguage: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val synopsis: String?,
    val genres: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val updatedMillis: Long,
)

/** No FK into provider inventory: refreshes may replace inventory rows. */
@Entity(tableName = "movie_identity_matches", primaryKeys = ["sourceId", "streamId"],
    indices = [Index("tmdbId"), Index("evidence")])
data class MovieIdentityMatch(
    val sourceId: Long,
    val streamId: String,
    val evidence: String,
    val tmdbId: Int?,
    val confidence: Double,
    val provenance: String,
    val manual: Boolean,
    val candidateIds: String,
)

data class CanonicalMovieCard(
    @Embedded val title: CanonicalMovie,
    val movieId: Long,
    val versionCount: Int,
    /** Highest inferred quality rank among versions (500 8K, 400 4K, 300 FHD, 200 HD, 100 SD, 0 unknown). */
    val bestQuality: Int,
)

enum class CatalogueSort { TITLE, YEAR, RATING, ADDED }

fun Movie.identityEvidence(): String = listOf(name, year?.toString().orEmpty(), tmdbId.orEmpty()).joinToString("\u001f")

/** Presentation copy only. Never persist this over the provider row. */
fun Movie.withCanonical(title: CanonicalMovie): Movie = copy(
    name = title.title, year = title.year, posterUrl = title.posterUrl,
    backdropUrl = title.backdropUrl, plot = title.synopsis, genre = title.genres,
    rating = title.rating, durationSeconds = title.runtimeMinutes?.times(60), tmdbId = title.tmdbId.toString(),
)

/** A failed import cannot replace the last complete provider snapshot. */
@Entity(tableName = "movie_inventory_snapshots")
data class MovieInventorySnapshot(@PrimaryKey val sourceId: Long, val generation: String)

@Entity(tableName = "movie_inventory_assets", primaryKeys = ["sourceId", "generation", "streamId"])
data class MovieInventoryAsset(val sourceId: Long, val generation: String, val streamId: String)
