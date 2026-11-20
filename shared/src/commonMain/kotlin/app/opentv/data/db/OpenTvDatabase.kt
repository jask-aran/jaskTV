/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.opentv.data.model.Category
import app.opentv.data.model.UserCollection
import app.opentv.data.model.CollectionEntry
import app.opentv.data.model.ChannelAliasOverride
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.Download
import app.opentv.data.model.MovieFts
import app.opentv.data.model.SeriesFts
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.MovieBrowseKey

class Converters {
    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name

    @TypeConverter fun stringToSourceKind(value: String): SourceKind =
        runCatching { SourceKind.valueOf(value) }.getOrDefault(SourceKind.M3U)

    @TypeConverter fun liveStreamFormatToString(value: LiveStreamFormat): String = value.name

    @TypeConverter fun stringToLiveStreamFormat(value: String): LiveStreamFormat =
        runCatching { LiveStreamFormat.valueOf(value) }.getOrDefault(LiveStreamFormat.HLS)

    @TypeConverter fun streamKindToString(value: StreamKind): String = value.name

    @TypeConverter fun stringToStreamKind(value: String): StreamKind =
        runCatching { StreamKind.valueOf(value) }.getOrDefault(StreamKind.LIVE)

    @TypeConverter fun recordingStatusToString(value: RecordingStatus): String = value.name

    @TypeConverter fun stringToRecordingStatus(value: String): RecordingStatus =
        runCatching { RecordingStatus.valueOf(value) }.getOrDefault(RecordingStatus.FAILED)
}

@Database(
    entities = [
        app.opentv.livetv.SavedTvChannel::class,
        app.opentv.catalogue.MovieInventorySnapshot::class,
        app.opentv.catalogue.MovieInventoryAsset::class,
        app.opentv.catalogue.CanonicalMovie::class,
        app.opentv.catalogue.CanonicalShow::class,
        app.opentv.catalogue.MovieIdentityMatch::class,
        Source::class,
        Category::class,
        Channel::class,
        EpgFeed::class,
        EpgChannelAlias::class,
        Programme::class,
        Movie::class,
        Series::class,
        Episode::class,
        PlaybackPosition::class,
        Profile::class,
        Recording::class,
        SeriesRule::class,
        Reminder::class,
        MovieFts::class,
        SeriesFts::class,
        Download::class,
        UserCollection::class,
        CollectionEntry::class,
        ChannelAliasOverride::class,
    ],
    version = 25,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@androidx.room.ConstructedBy(OpenTvDatabaseConstructor::class)
abstract class OpenTvDatabase : RoomDatabase() {
    abstract fun savedTv(): app.opentv.livetv.SavedTvDao
    abstract fun canonicalMovies(): app.opentv.catalogue.CanonicalMovieDao
    abstract fun canonicalShows(): app.opentv.catalogue.CanonicalShowDao
    abstract fun sources(): SourceDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun epgFeeds(): EpgFeedDao
    abstract fun epgAliases(): EpgChannelAliasDao
    abstract fun programmes(): ProgrammeDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun positions(): PlaybackPositionDao
    abstract fun profiles(): ProfileDao
    abstract fun recordings(): RecordingDao
    abstract fun seriesRules(): SeriesRuleDao
    abstract fun reminders(): ReminderDao
    abstract fun downloads(): DownloadDao
    abstract fun collections(): CollectionDao
    abstract fun channelAliases(): ChannelAliasDao

    companion object
}

/** Room generates the actual implementations for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object OpenTvDatabaseConstructor : androidx.room.RoomDatabaseConstructor<OpenTvDatabase> {
    override fun initialize(): OpenTvDatabase
}
