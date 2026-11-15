package app.opentv.catalogue

import app.opentv.data.parser.VodTitleCleaner
import java.text.Normalizer
import java.util.Locale

/** Search candidates are evidence, never an ordered list of answers. */
data class MovieCandidate(val id: Int, val title: String, val originalTitle: String, val year: Int?)

sealed interface MovieMatchDecision {
    data class Accepted(val candidate: MovieCandidate, val confidence: Double, val provenance: String) : MovieMatchDecision
    data class Ambiguous(val candidateIds: List<Int>) : MovieMatchDecision
    data object Unmatched : MovieMatchDecision
}

object MovieMatching {
    fun titleKey(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun releaseYear(raw: String, supplied: Int?): Int? = supplied ?: Regex("\\((19|20)\\d{2}\\)")
        .findAll(raw).lastOrNull()?.value?.removeSurrounding("(", ")")?.toIntOrNull()

    fun explicit(candidate: MovieCandidate, expectedYear: Int?, cached: Boolean = false): MovieMatchDecision =
        if (expectedYear != null && candidate.year != expectedYear) MovieMatchDecision.Ambiguous(listOf(candidate.id))
        else MovieMatchDecision.Accepted(candidate, 1.0, if (cached) "validated-provider-tmdb-cache" else "validated-provider-tmdb")

    fun query(raw: String): String = VodTitleCleaner.clean(raw)
        .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "").trim()

    fun decide(title: String, year: Int?, candidates: List<MovieCandidate>): MovieMatchDecision {
        val key = titleKey(query(title))
        if (key.isBlank()) return MovieMatchDecision.Unmatched
        val exact = candidates.filter { it.id > 0 &&
            (titleKey(it.title) == key || titleKey(it.originalTitle) == key) &&
            (year == null || it.year == year) }.distinctBy { it.id }
        return when {
            exact.isEmpty() -> MovieMatchDecision.Unmatched
            year == null || exact.size != 1 -> MovieMatchDecision.Ambiguous(exact.map { it.id })
            else -> MovieMatchDecision.Accepted(exact.single(), 0.98, "exact-title-year")
        }
    }
}
