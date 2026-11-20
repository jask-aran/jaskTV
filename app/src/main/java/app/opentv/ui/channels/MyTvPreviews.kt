package app.opentv.ui.channels

import app.opentv.ui.jaskFocusRing
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.opentv.data.db.PhoneGuideHorizon
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.ui.*
import app.opentv.ui.vod.JaskTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Fixed fixture clock: screenshots remain comparable, never represent actual broadcasts. */
@Composable
internal fun MyTvPrototype(width: Int, empty: Boolean = false) {
    val clock = 1_800_000_000_000L
    val channels = remember { listOf("BBC One", "Sky Sports F1", "National Geographic", "Channel without guide").mapIndexed { i, name ->
        val c = Channel(id = i.toLong() + 1, sourceId = 1, streamId = "$i", name = name,
            displayName = name, groupKey = "fixture-$i", categoryId = null, logoUrl = null,
            epgChannelId = "fixture-$i", number = null, streamUrl = "")
        ChannelsViewModel.Row(c, listOf(c), null, null, emptyList())
    } }
    val data = remember { object : PhoneGuideData {
        override val phoneGuideVersion = MutableStateFlow(0L)
        override suspend fun phoneGuideHorizon() = PhoneGuideHorizon(clock - 3_600_000, clock + 12 * 3_600_000)
        override suspend fun phoneProgrammes(visibleRows: List<ChannelsViewModel.Row>, window: PhoneGuideTimeline.Window, nowNext: Boolean): Map<Any, List<Programme>> =
            visibleRows.associate { row -> row.key to if (row.primary.id == 4L) emptyList() else listOf(
                Programme(id = row.primary.id * 10, feedId = 1, epgChannelId = "fixture", startUtcMillis = clock - 15 * 60_000, endUtcMillis = clock + 30 * 60_000, title = "Current programme"),
                Programme(id = row.primary.id * 10 + 1, feedId = 1, epgChannelId = "fixture", startUtcMillis = clock + 30 * 60_000, endUtcMillis = clock + 90 * 60_000, title = "Next programme")) }
    } }
    CompositionLocalProvider(LocalLayoutClass provides if (width < 600) LayoutClass.PHONE else LayoutClass.TABLET) {
        JaskTheme {
            JaskChromeFrame(JaskDestination.LIVE, {}, "", {}, {}, emptyList()) {
                Column(Modifier.fillMaxSize().padding(LocalJaskContentPadding.current)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("My TV", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton({}, modifier = Modifier.jaskFocusRing()) { Text("Add channels") }
                    }
                    if (empty) MyTvEmptyState({}) else PhoneTouchGuide(channels, clock, data, {}, { _, _ -> })
                }
            }
        }
    }
}
@Preview(name = "My TV · Phone portrait", widthDp = 390, heightDp = 844)
@Composable private fun PhonePortrait() = MyTvPrototype(390)
@Preview(name = "My TV · Phone landscape", widthDp = 844, heightDp = 390)
@Composable private fun PhoneLandscape() = MyTvPrototype(844)
@Preview(name = "My TV · Tablet portrait", widthDp = 800, heightDp = 1280)
@Composable private fun TabletPortrait() = MyTvPrototype(800)
@Preview(name = "My TV · Tablet landscape", widthDp = 1280, heightDp = 800)
@Composable private fun TabletLandscape() = MyTvPrototype(1280)
@Preview(name = "My TV · Empty", widthDp = 390, heightDp = 844)
@Composable private fun Empty() = MyTvPrototype(390, true)
