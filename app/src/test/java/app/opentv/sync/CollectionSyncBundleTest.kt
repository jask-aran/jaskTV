package app.opentv.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class CollectionSyncBundleTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun libraryProfileAndProviderStableMembersRoundTrip() {
        val input = SyncBundle(collections = listOf(
            SyncCollection("Me", "F1", 123, listOf(
                SyncCollectionMember("channel", "https://example.com/stream/1"),
                SyncCollectionMember("show", "42", "https://provider.example.com"),
            )),
            SyncCollection("Partner", "Movies", 456, emptyList()),
        ))
        val data = json.encodeToString(SyncBundle.serializer(), input)
        val parsed = json.decodeFromString(SyncBundle.serializer(), data)
        assertThat(parsed.version).isEqualTo(4)
        assertThat(parsed.collections).containsExactlyElementsIn(input.collections).inOrder()
    }

    @Test fun olderBundleStillLoadsWithoutCollections() {
        val data = """{"version":3,"profiles":["Me"],"positions":[]}"""
        val parsed = json.decodeFromString(SyncBundle.serializer(), data)
        assertThat(parsed.collections).isEmpty()
        assertThat(parsed.profiles).containsExactly("Me")
    }
}
