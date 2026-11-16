/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.UserCollection
import app.opentv.data.model.CollectionEntry
import kotlinx.coroutines.flow.first
import app.opentv.data.model.Profile
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.SmbClient
import kotlinx.serialization.json.Json

/**
 * Reads local state into a [SyncBundle] and applies a received one. The single place that knows
 * how OpenTV's tables map onto the portable, cross-device bundle, so the LAN server/client and the
 * NAS sync all agree on what travels and how it merges.
 *
 * Merge policy (see [SyncBundle]): watch positions are two-way newest-wins; curation (profiles,
 * favourites, hidden channels, hidden categories) and NAS recordings are applied **additively** —
 * we spread what a peer has without deleting anything locally. That keeps a sync from ever wiping a
 * favourite you set on one box, un-hiding the structural separator rows the importer hides, or
 * dropping a recording that only one device knows about.
 */
class SyncEngine(private val graph: ServiceLocator.Graph) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** What a single [apply] actually changed — summed across peers for the NAS "Synced N" line. */
    data class MergeResult(
        val positions: Int = 0,
        val favourites: Int = 0,
        val hidden: Int = 0,
        val recordings: Int = 0,
        val collections: Int = 0,
    ) {
        val total: Int get() = positions + favourites + hidden + recordings + collections

        operator fun plus(other: MergeResult): MergeResult = MergeResult(
            positions = positions + other.positions,
            favourites = favourites + other.favourites,
            hidden = hidden + other.hidden,
            recordings = recordings + other.recordings,
            collections = collections + other.collections,
        )
    }

    suspend fun gather(): SyncBundle {
        // WorkManager may sync before the Profiles screen ever opens. Ensure the built-in
        // viewer is present, using an idempotent insert to avoid racing another initializer.
        graph.profiles.insertDefault(Profile(id = 1L, name = "Me", createdAtMillis = 0))
        val profiles = graph.profiles.all()
        val names = profiles.associate { it.id to it.name }
        val positions = graph.playbackPositions.all().mapNotNull { pos ->
            val profileName = names[pos.profileId] ?: return@mapNotNull null
            val parts = pos.mediaKey.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            val streamUrl = when (parts[0]) {
                "movie" -> graph.catalogRepository.movie(id)?.streamUrl
                "ep" -> graph.catalogRepository.episode(id)?.streamUrl
                else -> null
            } ?: return@mapNotNull null
            SyncPosition(
                profile = profileName,
                streamUrl = streamUrl,
                kind = parts[0],
                positionMillis = pos.positionMillis,
                durationMillis = pos.durationMillis,
                updatedAtMillis = pos.updatedAtMillis,
            )
        }
        // Only finished recordings that live on the shared NAS travel — a device-local (file://)
        // capture can't be opened from another box, so sharing its metadata would just create a
        // library row that fails to play everywhere but here.
        val recordings = graph.recordingRepository.all()
            .filter { it.status == RecordingStatus.COMPLETED && SmbClient.isSmb(it.filePath) }
            .map { r ->
                SyncRecording(
                    channelName = r.channelName,
                    logoUrl = r.logoUrl,
                    title = r.title,
                    description = r.description,
                    filePath = r.filePath,
                    streamUrl = r.streamUrl,
                    userAgent = r.userAgent,
                    scheduledStartMillis = r.scheduledStartMillis,
                    scheduledEndMillis = r.scheduledEndMillis,
                    startedAtMillis = r.startedAtMillis,
                    endedAtMillis = r.endedAtMillis,
                    sizeBytes = r.sizeBytes,
                )
            }
        // Collection rows carry device-local IDs, so translate members to portable stream
        // identities before writing a bundle. Local downloads never travel as playable files.
        val collectionDao = graph.database.collections()
        val saved = collectionDao.allCollections()
        val members = collectionDao.allEntries().groupBy { it.collectionId }
        val portableCollections = saved.mapNotNull { collection ->
            val owner = names[collection.profileId] ?: return@mapNotNull null
            val items = members[collection.id].orEmpty().mapNotNull { item ->
                when (item.kind) {
                    "channel" -> graph.database.channels().byProviderKey(item.sourceId, item.itemKey)
                        ?.streamUrl?.let { SyncCollectionMember("channel", it) }
                    "movie" -> graph.database.movies().byProviderKey(item.sourceId, item.itemKey)
                        ?.streamUrl?.let { SyncCollectionMember("movie", it) }
                    "show" -> {
                        val source = graph.database.sources().byId(item.sourceId)
                        source?.url?.let { SyncCollectionMember("show", item.itemKey, it) }
                    }
                    else -> null // Files downloaded on one device aren't on another.
                }
            }
            SyncCollection(owner, collection.name, collection.createdMillis, items)
        }

        return SyncBundle(
            myTv = graph.database.savedTv().all().mapNotNull { item ->
                val profile = names[item.profileId] ?: return@mapNotNull null
                if (item.identity.startsWith("provider:")) null
                else SyncSavedTv(profile, item.identity, item.label, item.sortIndex)
            },
            generatedAtMillis = System.currentTimeMillis(),
            profiles = profiles.map { it.name },
            positions = positions,
            collections = portableCollections,
            favouriteChannels = graph.catalogRepository.favouriteChannelUrls(),
            hiddenChannels = graph.catalogRepository.hiddenChannelUrls(),
            favouriteMovies = graph.catalogRepository.favouriteMovieUrls(),
            favouriteSeries = graph.catalogRepository.favouriteSeriesIds(),
            hiddenCategories = graph.settings.hiddenCategories.value.toList(),
            recordings = recordings,
        )
    }

    fun encode(bundle: SyncBundle): String = json.encodeToString(SyncBundle.serializer(), bundle)

    fun decode(bundleJson: String): SyncBundle =
        json.decodeFromString(SyncBundle.serializer(), bundleJson)

    /** Applies a received bundle. Returns what changed, so callers can report "Synced N items". */
    suspend fun apply(bundle: SyncBundle): MergeResult {
        // Profiles — add any the peer has that we're missing. Never remove (that would delete a
        // local profile's watch history).
        for (name in bundle.profiles) {
            if (name.isNotBlank() && graph.profiles.byName(name) == null) {
                runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }
            }
        }

        // Curation — additive union, keyed by stable identifiers. Count only genuinely new keys
        // (ones we hadn't already curated) so the "Synced N" line means "new to this device".
        val localFavChannels = graph.catalogRepository.favouriteChannelUrls().toHashSet()
        val localHiddenChannels = graph.catalogRepository.hiddenChannelUrls().toHashSet()
        val localFavMovies = graph.catalogRepository.favouriteMovieUrls().toHashSet()
        val localFavSeries = graph.catalogRepository.favouriteSeriesIds().toHashSet()

        var favourites = 0
        var hidden = 0
        for (url in bundle.favouriteChannels) {
            graph.catalogRepository.markChannelFavouriteByUrl(url)
            if (localFavChannels.add(url)) favourites++
        }
        for (url in bundle.hiddenChannels) {
            graph.catalogRepository.markChannelHiddenByUrl(url)
            if (localHiddenChannels.add(url)) hidden++
        }
        for (url in bundle.favouriteMovies) {
            graph.catalogRepository.markMovieFavouriteByUrl(url)
            if (localFavMovies.add(url)) favourites++
        }
        for (seriesId in bundle.favouriteSeries) {
            graph.catalogRepository.markSeriesFavouriteBySeriesId(seriesId)
            if (localFavSeries.add(seriesId)) favourites++
        }
        if (bundle.hiddenCategories.isNotEmpty()) {
            graph.settings.setHiddenCategories(
                graph.settings.hiddenCategories.value + bundle.hiddenCategories,
            )
        }

        // Canonical My TV saves contain no stream URLs, provider credentials or local row IDs.
        for (remote in bundle.myTv.take(1500)) {
            if (remote.identity.length > 240 || remote.label.length > 240 ||
                !(remote.identity.startsWith("iptv:") || remote.identity.startsWith("group:"))) continue
            val profile = profileIdFor(remote.profile) ?: continue
            if (graph.database.savedTv().observe(profile).first().none { it.identity == remote.identity }) {
                graph.database.savedTv().save(app.opentv.livetv.SavedTvChannel(profile, remote.identity,
                    remote.label, null, null, remote.sortIndex.coerceAtLeast(0)))
            }
        }

        // Personal collections: resolve profile by name and all item IDs against this device's
        // own catalogue. Merge is additive in v1; deleted/renamed entries cannot yet be propagated
        // reliably because older peer snapshots may resurrect them.
        var collectionsMerged = 0
        val collectionDao = graph.database.collections()
        val sources = graph.database.sources().all()
        for (remote in bundle.collections.take(100)) {
            if (remote.profile.isBlank() || remote.name.isBlank() || remote.name.length > 60) continue
            val profileId = profileIdFor(remote.profile) ?: continue
            val existing = collectionDao.observeCollections(profileId).first()
                .firstOrNull { it.name.equals(remote.name, ignoreCase = true) }
            val id = if (existing != null) existing.id else collectionDao.create(
                UserCollection(name = remote.name, createdMillis = remote.createdMillis,
                    profileId = profileId)
            ).also { collectionsMerged++ }
            val localMembers = collectionDao.observeEntries(id).first().toHashSet()
            for (member in remote.members.take(1500)) {
                val resolved = when (member.kind) {
                    "channel" -> graph.database.channels().byStreamUrl(member.key)
                        ?.let { CollectionEntry(id, "channel", it.sourceId, it.streamId) }
                    "movie" -> graph.database.movies().byStreamUrl(member.key)
                        ?.let { CollectionEntry(id, "movie", it.sourceId, it.streamId) }
                    "show" -> sources.firstOrNull { it.url == member.providerUrl }
                        ?.let { source ->
                            graph.database.series().byProviderId(source.id, member.key)
                                ?.let { CollectionEntry(id, "show", it.sourceId, it.seriesId) }
                        }
                    else -> null
                }
                if (resolved != null && localMembers.add(resolved)) {
                    collectionDao.add(resolved)
                    collectionsMerged++
                }
            }
        }

        // NAS recordings — insert any smb:// recording we don't already have, keyed by its locator.
        // Never touch an existing row; a peer's copy and ours are the same file on the same NAS.
        var recordings = 0
        for (rec in bundle.recordings) {
            if (!SmbClient.isSmb(rec.filePath)) continue
            if (graph.recordingRepository.byFilePath(rec.filePath) != null) continue
            val inserted = runCatching {
                graph.recordingRepository.insert(
                    Recording(
                        // Ids from the source device mean nothing here; the row is self-contained.
                        channelId = 0,
                        sourceId = 0,
                        channelName = rec.channelName,
                        logoUrl = rec.logoUrl,
                        title = rec.title,
                        description = rec.description,
                        filePath = rec.filePath,
                        streamUrl = rec.streamUrl,
                        userAgent = rec.userAgent,
                        scheduledStartMillis = rec.scheduledStartMillis,
                        scheduledEndMillis = rec.scheduledEndMillis,
                        startedAtMillis = rec.startedAtMillis,
                        endedAtMillis = rec.endedAtMillis,
                        status = RecordingStatus.COMPLETED,
                        sizeBytes = rec.sizeBytes,
                    ),
                )
            }.isSuccess
            if (inserted) recordings++
        }

        // Watch positions — two-way, newest wins.
        var positions = 0
        for (item in bundle.positions) {
            val profileId = profileIdFor(item.profile) ?: continue
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(item.streamUrl)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(item.streamUrl)?.id
                else -> null
            } ?: continue
            val mediaKey = "${item.kind}:$localId"
            val existing = graph.playbackPositions.get(profileId, mediaKey)
            if (existing == null || item.updatedAtMillis > existing.updatedAtMillis) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = profileId,
                        mediaKey = mediaKey,
                        positionMillis = item.positionMillis,
                        durationMillis = item.durationMillis,
                        updatedAtMillis = item.updatedAtMillis,
                    ),
                )
                positions++
            }
        }
        return MergeResult(
            positions = positions,
            favourites = favourites,
            hidden = hidden,
            recordings = recordings,
            collections = collectionsMerged,
        )
    }

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }
}
