package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.livetv.SavedTvChannel
import app.opentv.livetv.LiveChannelKeys
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class MyTvTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenTvDatabase::class.java).build()
    @After fun close() = db.close()
    @Test fun `saved identity is unique per profile and survives absent provider assets`() = runTest {
        val saved = SavedTvChannel(1, "iptv:SkySportsF1.uk", "Sky Sports F1", 50, "999", 0)
        db.savedTv().save(saved)
        db.savedTv().save(saved.copy(preferredStreamId = "1000"))
        db.savedTv().save(saved.copy(profileId = 2))
        assertThat(db.savedTv().observe(1).first()).containsExactly(saved.copy(preferredStreamId = "1000"))
        db.savedTv().remove(1, saved.identity)
        assertThat(db.savedTv().observe(1).first()).isEmpty()
        assertThat(db.savedTv().observe(2).first()).containsExactly(saved.copy(profileId = 2))
    }
    @Test fun `country evidence is retained independently from quality`() {
        assertThat(LiveChannelKeys.region("UK | ESPN HD")).isEqualTo("GB")
        assertThat(LiveChannelKeys.region("US: ESPN 4K")).isEqualTo("US")
        assertThat(LiveChannelKeys.region("ESPN HD")).isEqualTo("unknown")
    }
    @Test fun `automatic guide IDs require exact name evidence and manual mappings override`() {
        val channel = app.opentv.data.model.Channel(sourceId = 1, streamId = "1", name = "UK: BBC One HD", groupKey = "bbcone",
            categoryId = null, logoUrl = null, epgChannelId = "wrong", matchedEpgId = "regional", number = null, streamUrl = "")
        val aliases = listOf(app.opentv.data.model.EpgChannelAlias(1, "wrong", "BBC Two", "bbctwo"),
            app.opentv.data.model.EpgChannelAlias(1, "regional", "BBC One Wales", "bbconewales"))
        assertThat(app.opentv.livetv.GuideEvidence.ids(channel, aliases)).isEmpty()
        assertThat(app.opentv.livetv.GuideEvidence.ids(channel.copy(epgOverrideId = "manual"), aliases)).containsExactly("manual")
    }

}
