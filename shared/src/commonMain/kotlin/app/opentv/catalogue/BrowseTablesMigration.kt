package app.opentv.catalogue

/**
 * Database 24 briefly had precomputed tables for the Movies and Shows "All" grids (movie_browse, series_browse). The
 * All grids were removed, so version 25 drops them. A device still on 23 goes straight to 25 with nothing to do.
 */
object BrowseTablesMigration {
    val drop24to25 = listOf("DROP TABLE IF EXISTS `movie_browse`", "DROP TABLE IF EXISTS `series_browse`")
}
