package app.opentv.widget

/** No playback URL, portal command, source credentials or programme metadata crosses this boundary. */
data class RecentChannelCandidate(
    val id: Long,
    val displayName: String,
    val customName: String?,
    val groupKey: String,
    val qualityRank: Int,
    val categoryId: String?,
    val logoUrl: String?,
    val categoryName: String?,
) {
    val name: String get() = customName?.takeIf { it.isNotBlank() } ?: displayName
}
