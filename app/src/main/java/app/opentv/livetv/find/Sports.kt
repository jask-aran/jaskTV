package app.opentv.livetv.find

/**
 * Sports a profile can follow. [espnPath] is the ESPN site API `sports/<path>/scoreboard` league
 * (unofficial and undocumented: it can change without notice). Formula 1 uses Jolpica, MotoGP its own
 * public (undocumented) schedule API, cricket ESPN's all-cricket header feed. Broadcasters come from ESPN only for US-centred leagues.
 */
data class Sport(val key: String, val label: String, val espnPath: String?, val note: String? = null,
    val shape: SportShape = SportShape.LEAGUE,
    /** The sport's own mark (ESPN's league logos where they exist) and colour, for its tile. */
    val logo: String? = null, val colour: Long = 0xFF2E3A46)

/** How a sport's schedule is laid out, which decides how the timeline presents it. */
enum class SportShape {
    /** Many games at once (NBA, NFL): a grid of games per start time. */
    LEAGUE,
    /** One event at a time, run over a weekend of sessions (motorsport): one card per weekend. */
    MEETING,
    /** Series and tournaments everywhere at once, some over days (cricket): grouped by series. */
    SERIES,
}

object Sports {
    val all = listOf(
        Sport("nba", "NBA", "basketball/nba", logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png", colour = 0xFF1D428A),
        Sport("f1", "Formula 1", null, "Sessions from Jolpica", SportShape.MEETING, "https://a.espncdn.com/i/teamlogos/leagues/500/f1.png", 0xFFB0120A),
        Sport("motogp", "MotoGP", null, "Sessions from MotoGP's schedule", SportShape.MEETING, colour = 0xFF8E1218),
        Sport("indycar", "IndyCar", "racing/irl", shape = SportShape.MEETING, colour = 0xFF1B3F8B),
        Sport("nascar", "NASCAR", "racing/nascar-premier", shape = SportShape.MEETING, colour = 0xFF13306B),
        Sport("cricket", "Cricket", null, "Internationals and leagues worldwide", SportShape.SERIES, colour = 0xFF1E6B3A),
        Sport("nfl", "NFL", "football/nfl", logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png", colour = 0xFF013369),
        Sport("nhl", "NHL", "hockey/nhl", logo = "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png", colour = 0xFF2B2B2B),
        Sport("mlb", "MLB", "baseball/mlb", logo = "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png", colour = 0xFF041E42),
        Sport("ncaab", "College basketball", "basketball/mens-college-basketball"),
        Sport("ncaaf", "College football", "football/college-football"),
        Sport("epl", "Premier League", "soccer/eng.1", colour = 0xFF3D195B),
        Sport("ucl", "Champions League", "soccer/uefa.champions", colour = 0xFF0A1A5C),
        Sport("aleague", "A-League Men", "soccer/aus.1"),
        Sport("afl", "AFL", "australian-football/afl", colour = 0xFF0B2A4A),
        Sport("nrl", "NRL", "rugby-league/3", colour = 0xFF0B5E2B),
        Sport("ufc", "UFC", "mma/ufc", logo = "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png", colour = 0xFF7A0A0A),
    )
    fun byKey(key: String): Sport? = all.firstOrNull { it.key == key }
}
