package app.opentv.ui.channels

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.LiveFindRow
import app.opentv.discovery.IptvOrgCatalog
import app.opentv.livetv.find.*
import app.opentv.ui.settings.JaskPill
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Local only: renders Find's production rows with the private provider list and saved ESPN samples
 * from the ignored .local folder. Output: .local/find-*.png (never committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveFindRenderTest {
    private val compose = createComposeRule()
    private val host = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java)).apply {
                exported = true; theme = android.R.style.Theme_Material_NoActionBar
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(host).around(compose)

    private val local = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, ".local") }
        .firstOrNull { File(it, "get_live_streams.json").isFile }

    private fun render(name: String, shots: List<Pair<String, String>> = emptyList(), content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            app.opentv.ui.vod.JaskTheme { Column(Modifier.fillMaxSize().background(Color(0xFF0E0D0C))) { content() } }
        }
        compose.waitForIdle()
        repeat(40) { compose.mainClock.advanceTimeBy(100); Thread.sleep(200); shadowOf(android.os.Looper.getMainLooper()).idle() }
        compose.waitForIdle()
        val v = checkNotNull(view)
        val bitmap = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { v.draw(android.graphics.Canvas(bitmap)) }
        File(local, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // Further frames scrolled to a list key, as a D-pad would bring them on screen.
        shots.forEach { (suffix, key) ->
            val lists = compose.onAllNodes(androidx.compose.ui.test.hasScrollToKeyAction()).fetchSemanticsNodes().indices
            check(lists.any { i -> runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollToKeyAction())[i].performScrollToKey(key) }.isSuccess }) { "No list has $key" }
            repeat(20) { compose.mainClock.advanceTimeBy(100); Thread.sleep(150); shadowOf(android.os.Looper.getMainLooper()).idle() }
            val frame = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            compose.runOnIdle { v.draw(android.graphics.Canvas(frame)) }
            File(local, "$name-$suffix.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private class Data(val index: LiveFindIndex, val cards: Map<String, List<EventCard>>, val sportChannels: Map<String, List<FindChannel>>,
        val myTv: List<FindChannel>)
    private val regions = setOf("AU", "US", "GB", "IN")
    private val followed = setOf("nba", "f1", "motogp", "cricket")

    private fun load(): Data = runBlocking {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val context = ApplicationProvider.getApplicationContext<Application>()
        File(local, "sports-samples/iptvorg-channels.json").copyTo(File(context.cacheDir, "jasktv-iptvorg-channels.json"), overwrite = true)
        val catalog = IptvOrgCatalog(context).also { check(it.warmFromDisk()) }
        val json = kotlinx.serialization.json.Json
        fun arr(f: File) = json.parseToJsonElement(f.readText()) as kotlinx.serialization.json.JsonArray
        fun kotlinx.serialization.json.JsonElement.s(k: String) =
            ((this as kotlinx.serialization.json.JsonObject)[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val cats = arr(File(local, "get_live_categories.json")).associate { it.s("category_id") to it.s("category_name") }
        val rows = arr(File(local, "get_live_streams.json")).mapIndexed { i, it ->
            val n = it.s("name").orEmpty()
            LiveFindRow(i.toLong(), 1, n, "", 0, it.s("category_id"), it.s("stream_icon")?.takeIf(String::isNotBlank), cats[it.s("category_id")])
        }
        File("/tmp/claude-1000/-home-jask-jaskTV/79a34747-a71b-4d82-a55d-50f70c564707/scratchpad/pub/logos.json").takeIf { it.isFile }
            ?.copyTo(File(context.cacheDir, "jasktv-iptvorg-logos.json"), overwrite = true)
        catalog.warmLogos(network = false)
        val index = LiveFinder.buildIndex(rows, { name, country -> catalog.strictIn(name, country) }, catalog::logoFor)
        val samples = File(local!!, "sports-samples")
        val now = System.currentTimeMillis()
        val events = (listOf("espn_nba_20261117.json", "espn_nba_20261118.json").flatMap { SportsSchedule.parseScoreboard(Sports.byKey("nba")!!, File(samples, it).readText()) } +
            SportsSchedule.parseCricket(File(samples, "espn_cricket3.json").readText(), now) +
            // The app reads F1 from Jolpica; ESPN's copy of the same weekend stands in here.
            SportsSchedule.parseRacing(Sports.byKey("f1")!!, File(samples, "espn_racing_f1.json").readText()) +
            SportsSchedule.parseMotoGp(File(samples, "motogp_2026.json").readText()))
            .distinctBy { it.id }.map { EventMatcher.settle(it, now) }
            .filter { (it.live || it.startUtc >= now - 86_400_000L) && it.startUtc <= now + 7 * 86_400_000L }
        val finder = SportsFinder(index)
        val cards = finder.cards(events, regions).groupBy { it.event.sport }
        val mine = index.channels.filter { it.region in regions }
        val byGenre = mine.groupBy { it.genre }.toSortedMap(compareBy { it.ordinal })

        val sportChannels = followed.associateWith { finder.sportChannels(it, regions) }
        // Logos load synchronously so they are drawn in the captured frame (public CDNs and the provider's logo URLs).
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(context).crossfade(false).allowHardware(false).okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", app.opentv.OpenTvApp.IMAGE_USER_AGENT).build()) }.build()
        }.build())
        val myTv = listOf("ABC News Australia", "Fox Cricket", "Sky Sports F1", "CNN").mapNotNull { n -> index.channels.firstOrNull { it.name.equals(n, true) } }
        Data(index, cards, sportChannels, myTv)
    }

    @Composable private fun Shell(content: @Composable () -> Unit) =
        app.opentv.ui.JaskChromeFrame(app.opentv.ui.JaskDestination.LIVE, {}, "", {}, {}, emptyList(), content = content)

    @Test @Config(qualifiers = "w412dp-h2700dp-xhdpi") fun home() {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val d = load()
        render("find-home") { Shell { LiveFindList(d.index, regions, "", followed, d.cards, d.sportChannels, null, {}, {}, {}, {}, channelActions = false, myTv = d.myTv, onGuide = {}) } }
    }

    @Test @Config(qualifiers = "w412dp-h2400dp-xhdpi") fun cricket() {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val d = load()
        render("find-cricket") { Shell { LiveFindList(d.index, regions, "", followed, d.cards, d.sportChannels, null, {}, {}, {}, {}, channelActions = false,
            initialPage = "sport:cricket") } }
    }

    /** The NBA page: the week, then a day of games. */
    @Test @Config(qualifiers = "w412dp-h2400dp-xhdpi") fun nbaNight() {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val d = load()
        render("find-nba") { Shell { LiveFindList(d.index, regions, "", followed, d.cards, d.sportChannels, null, {}, {}, {}, {}, channelActions = false,
            initialPage = "sport:nba") } }
    }

    /** A landscape TV (1920×1080 at 2×): the home as it opens, then scrolled to What's on. */
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi") fun tv() {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val d = load()
        render("find-tv", listOf("whats-on" to "timeline-title")) { androidx.compose.runtime.CompositionLocalProvider(app.opentv.ui.LocalLayoutClass provides app.opentv.ui.LayoutClass.TV) {
            Shell { LiveFindList(d.index, regions, "", followed, d.cards, d.sportChannels, null, {}, {}, {}, {}, channelActions = false, myTv = d.myTv, onGuide = {}) } } }
    }

    /** The F1 page on a landscape TV. */
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi") fun tvSport() {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val d = load()
        render("find-tv-f1") { androidx.compose.runtime.CompositionLocalProvider(app.opentv.ui.LocalLayoutClass provides app.opentv.ui.LayoutClass.TV) {
            Shell { LiveFindList(d.index, regions, "", followed, d.cards, d.sportChannels, null, {}, {}, {}, {}, channelActions = false,
                initialPage = "sport:f1") } } }
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi") fun newsPage() {
        assumeTrue(local != null && File(local, "sports-samples/iptvorg-channels.json").isFile)
        val d = load()
        render("find-news") { Shell { LiveFindList(d.index, regions, "", followed, d.cards, d.sportChannels, null, {}, {}, {}, {}, channelActions = false, initialPage = "NEWS") } }
    }
}
