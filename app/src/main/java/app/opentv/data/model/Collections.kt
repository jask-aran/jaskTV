/*
 * jaskTV collection model — GPL-3.0-or-later.
 * Members use provider-stable keys, not local row IDs, so catalogue refreshes do not orphan them.
 */
package app.opentv.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_collections")
data class UserCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdMillis: Long,
)

@Entity(tableName = "collection_entries", primaryKeys = ["collectionId", "kind", "sourceId", "itemKey"])
data class CollectionEntry(
    val collectionId: Long,
    /** channel, movie, show or download */
    val kind: String,
    /** Provider ID, or zero for downloads. */
    val sourceId: Long,
    /** Provider's streamId / seriesId, or Download.mediaKey. */
    val itemKey: String,
)

@Entity(tableName = "channel_alias_overrides", primaryKeys = ["sourceId", "streamId"])
data class ChannelAliasOverride(
    val sourceId: Long,
    val streamId: String,
    /** The iptv-org stable ID (e.g. SkySportsF1.uk), if selected by the user. */
    val iptvOrgId: String,
    /** Optional user-defined alternate label; never rewrites original provider name. */
    val label: String = "",
)
