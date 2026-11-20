package app.opentv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import androidx.room.RoomDatabase.JournalMode

/**
 * Android database setup: the file under the app's database directory and every migration from
 * v2. Desktop opens a copy of an already-migrated database, so migrations live here only.
 */
object OpenTvDatabaseAndroid {
        /**
         * v2 → v3: profiles. Adds the profiles table with a default profile (id 1, "Me"), and
         * re-keys resume positions by (profileId, mediaKey) — existing positions all become the
         * default profile's, so nobody loses their place. Written to match Room's own DDL so the
         * identity check passes. Only historical v1 has a destructive fallback.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
                )
                db.execSQL("INSERT INTO `profiles` (`id`, `name`, `createdAtMillis`) VALUES (1, 'Me', 0)")
                db.execSQL(
                    "CREATE TABLE `playback_positions_new` (" +
                        "`profileId` INTEGER NOT NULL, `mediaKey` TEXT NOT NULL, " +
                        "`positionMillis` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, " +
                        "`updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `mediaKey`))",
                )
                db.execSQL(
                    "INSERT INTO `playback_positions_new` " +
                        "(`profileId`, `mediaKey`, `positionMillis`, `durationMillis`, `updatedAtMillis`) " +
                        "SELECT 1, `mediaKey`, `positionMillis`, `durationMillis`, `updatedAtMillis` " +
                        "FROM `playback_positions`",
                )
                db.execSQL("DROP TABLE `playback_positions`")
                db.execSQL("ALTER TABLE `playback_positions_new` RENAME TO `playback_positions`")
            }
        }

        /**
         * v3 → v4: recordings. Purely additive — two new tables (recordings, series_rules) and
         * their indices, nothing existing is touched. Written to match Room's generated DDL so
         * the schema-identity check passes and favourites/overrides survive the upgrade (rather
         * than falling through to the destructive rebuild below).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recordings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` INTEGER NOT NULL, " +
                        "`sourceId` INTEGER NOT NULL, " +
                        "`channelName` TEXT NOT NULL, " +
                        "`logoUrl` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`streamUrl` TEXT NOT NULL, " +
                        "`userAgent` TEXT NOT NULL, " +
                        "`scheduledStartMillis` INTEGER NOT NULL, " +
                        "`scheduledEndMillis` INTEGER NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`endedAtMillis` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`seriesRuleId` INTEGER, " +
                        "`error` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_status` ON `recordings` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_channelId` ON `recordings` (`channelId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_rules` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` INTEGER NOT NULL, " +
                        "`channelName` TEXT NOT NULL, " +
                        "`titleKey` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_rules_channelId` ON `series_rules` (`channelId`)")
            }
        }

        /**
         * v4 → v5: catch-up. Adds the archive columns to channels. Additive; the DEFAULT 0 matches
         * the entity's @ColumnInfo(defaultValue = "0") so the schema-identity check passes.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `tvArchive` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `tvArchiveDays` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: programme reminders. Purely additive — one new table (reminders) and its two
         * indices, matching Room's generated DDL so favourites, recordings and overrides survive
         * the upgrade rather than falling through to the destructive rebuild.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`channelId` INTEGER NOT NULL, " +
                        "`channelName` TEXT NOT NULL, " +
                        "`logoUrl` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`startUtcMillis` INTEGER NOT NULL, " +
                        "`endUtcMillis` INTEGER NOT NULL, " +
                        "`autoTune` INTEGER NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "`fired` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_startUtcMillis` ON `reminders` (`startUtcMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_channelId` ON `reminders` (`channelId`)")
            }
        }

        /**
         * v6 → v7: per-source live-stream format. Adds the `liveFormat` column to sources. Additive;
         * the DEFAULT 'HLS' back-fills existing rows to the historical behaviour so nobody's channels
         * change container until they opt in. The entity carries no @ColumnInfo(defaultValue) — like
         * the other enum-as-String columns (`kind`) — so the DB-only default is not part of the
         * schema-identity check and the upgrade preserves favourites and overrides.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `liveFormat` TEXT NOT NULL DEFAULT 'HLS'")
            }
        }

        /**
         * v7 → v8: per-channel custom name. Adds the nullable `customName` column to channels — the
         * user's manual rename, carried across re-syncs by [ChannelDao.replaceCatalogue]. Additive
         * and nullable (no NOT NULL, no default) to match the nullable Kotlin `String?`, so the
         * schema-identity check passes and favourites/overrides survive the upgrade.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `customName` TEXT")
            }
        }

        /**
         * v8 → v9: richer VOD metadata for the Netflix-style Movies/Series redesign. Adds the
         * enrichment columns (backdrop, cast, genre, tmdbId, and — movies only — director) pulled
         * from the provider's own Xtream `get_vod_info`/`get_series_info`. Every column is nullable
         * TEXT with no default, matching the nullable Kotlin `String?` fields, so the schema-identity
         * check passes and favourites/overrides survive the upgrade rather than falling through to
         * the destructive rebuild.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `backdropUrl` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `cast` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `genre` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `tmdbId` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `director` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `backdropUrl` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `cast` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `genre` TEXT")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `tmdbId` TEXT")
            }
        }

        /** Recordings gain a profile owner so a booking can be tagged to whoever set it. Nullable
         *  (no default) to match Room's generated DDL for a `Long?` field — the project's pattern. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `profileId` INTEGER")
            }
        }

        /**
         * v10 → v11: Stalker/Ministra portal support. Adds a nullable `macAddress` to sources (the
         * portal credential) and a nullable `cmd` to channels (the play command resolved on demand
         * at tune time). Both nullable TEXT with no default, matching the `String?` fields, so the
         * schema-identity check passes and favourites/overrides survive the upgrade.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `macAddress` TEXT")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `cmd` TEXT")
            }
        }

        /**
         * 11 → 12: newest-first indexes on films/series, and FTS4 title indexes (movies_fts,
         * series_fts) with Room's content-sync triggers. The final 'rebuild' fills the new indexes
         * from the rows already on disk, so search works without a re-sync.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_addedMillis` ON `movies` (`addedMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_addedMillis` ON `series` (`addedMillis`)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `movies_fts` USING FTS4(`name` TEXT NOT NULL, tokenize=unicode61, content=`movies`)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_BEFORE_UPDATE BEFORE UPDATE ON `movies` BEGIN DELETE FROM `movies_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_BEFORE_DELETE BEFORE DELETE ON `movies` BEGIN DELETE FROM `movies_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_AFTER_UPDATE AFTER UPDATE ON `movies` BEGIN INSERT INTO `movies_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_AFTER_INSERT AFTER INSERT ON `movies` BEGIN INSERT INTO `movies_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `series_fts` USING FTS4(`name` TEXT NOT NULL, tokenize=unicode61, content=`series`)")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_series_fts_BEFORE_UPDATE BEFORE UPDATE ON `series` BEGIN DELETE FROM `series_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_series_fts_BEFORE_DELETE BEFORE DELETE ON `series` BEGIN DELETE FROM `series_fts` WHERE `docid`=OLD.`rowid`; END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_series_fts_AFTER_UPDATE AFTER UPDATE ON `series` BEGIN INSERT INTO `series_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
                db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_series_fts_AFTER_INSERT AFTER INSERT ON `series` BEGIN INSERT INTO `series_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END")
                db.execSQL("INSERT INTO `movies_fts`(`movies_fts`) VALUES('rebuild')")
                db.execSQL("INSERT INTO `series_fts`(`series_fts`) VALUES('rebuild')")
            }
        }

        /** 12 → 13: the Downloads tab's table (films/episodes saved for offline viewing). */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `posterUrl` TEXT, `tag` TEXT, `seriesTitle` TEXT, `season` INTEGER, `episode` INTEGER, `sourceUrl` TEXT NOT NULL, `systemId` INTEGER NOT NULL, `createdMillis` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_mediaKey` ON `downloads` (`mediaKey`)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `localPlaylistFileName` TEXT")
                db.execSQL("ALTER TABLE `sources` ADD COLUMN `localPlaylistDisplayName` TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `browseGroupKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `browseQualityRank` INTEGER NOT NULL DEFAULT 0")
                // Keyset batches bound memory on large catalogues. No OFFSET, window functions or
                // SQL normalization: use exactly the Kotlin grouping rules, even on API 23 SQLite.
                var afterId: Long? = null
                val update = db.compileStatement(
                    "UPDATE movies SET browseGroupKey = ?, browseQualityRank = ? WHERE id = ?",
                )
                try {
                    while (true) {
                        val batch = mutableListOf<Pair<Long, MovieBrowseKey>>()
                        val sql = if (afterId == null) "SELECT id, name, year FROM movies ORDER BY id LIMIT 500"
                        else "SELECT id, name, year FROM movies WHERE id > ? ORDER BY id LIMIT 500"
                        val args = afterId?.let { arrayOf<Any>(it) } ?: emptyArray()
                        db.query(sql, args).use { cursor ->
                            while (cursor.moveToNext()) {
                                batch += cursor.getLong(0) to MovieBrowseKey.from(
                                    cursor.getString(1), if (cursor.isNull(2)) null else cursor.getInt(2),
                                )
                            }
                        }
                        if (batch.isEmpty()) break
                        for ((id, key) in batch) {
                            update.bindString(1, key.groupKey)
                            update.bindLong(2, key.qualityRank.toLong())
                            update.bindLong(3, id)
                            update.executeUpdateDelete()
                        }
                        afterId = batch.last().first
                    }
                } finally {
                    update.close()
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_browseGroupKey_browseQualityRank_name_id` ON `movies` (`browseGroupKey`, `browseQualityRank`, `name`, `id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_categoryId_browseGroupKey` ON `movies` (`categoryId`, `browseGroupKey`)")
            }
        }

        /** v15 → v16: additive saved collections and manual canonical channel mappings. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdMillis` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `collection_entries` (`collectionId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `itemKey` TEXT NOT NULL, PRIMARY KEY(`collectionId`, `kind`, `sourceId`, `itemKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `channel_alias_overrides` (`sourceId` INTEGER NOT NULL, `streamId` TEXT NOT NULL, `iptvOrgId` TEXT NOT NULL, `label` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `streamId`))")
            }
        }

        /** v16 → v17: the existing personal collections are owned by default profile 1. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_collections` ADD COLUMN `profileId` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_collections_profileId` ON `user_collections` (`profileId`)")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `canonical_movies` (`tmdbId` INTEGER NOT NULL, `title` TEXT COLLATE NOCASE NOT NULL, `originalTitle` TEXT NOT NULL, `year` INTEGER, `originalLanguage` TEXT, `posterUrl` TEXT, `backdropUrl` TEXT, `synopsis` TEXT, `genres` TEXT, `rating` REAL, `runtimeMinutes` INTEGER, `updatedMillis` INTEGER NOT NULL, PRIMARY KEY(`tmdbId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_movies_title` ON `canonical_movies` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_canonical_movies_originalLanguage` ON `canonical_movies` (`originalLanguage`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `movie_identity_matches` (`sourceId` INTEGER NOT NULL, `streamId` TEXT NOT NULL, `evidence` TEXT NOT NULL, `tmdbId` INTEGER, `confidence` REAL NOT NULL, `provenance` TEXT NOT NULL, `manual` INTEGER NOT NULL, `candidateIds` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `streamId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_identity_matches_evidence` ON `movie_identity_matches` (`evidence`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_identity_matches_tmdbId` ON `movie_identity_matches` (`tmdbId`)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `movie_inventory_snapshots` (`sourceId` INTEGER NOT NULL, `generation` TEXT NOT NULL, PRIMARY KEY(`sourceId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `movie_inventory_assets` (`sourceId` INTEGER NOT NULL, `generation` TEXT NOT NULL, `streamId` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `generation`, `streamId`))")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `saved_tv_channels` (`profileId` INTEGER NOT NULL, `identity` TEXT NOT NULL, `label` TEXT NOT NULL, `preferredSourceId` INTEGER, `preferredStreamId` TEXT, `sortIndex` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `identity`))")
            }
        }

        /** Additive: TMDB TV metadata per provider hint (see CanonicalShow). */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `canonical_shows` (`tmdbId` INTEGER NOT NULL, `validated` INTEGER NOT NULL, `title` TEXT COLLATE NOCASE, `originalTitle` TEXT, `year` INTEGER, `originalLanguage` TEXT, `posterUrl` TEXT, `backdropUrl` TEXT, `synopsis` TEXT, `genres` TEXT, `rating` REAL, `seasonCount` INTEGER, `checkedMillis` INTEGER NOT NULL, PRIMARY KEY(`tmdbId`))")
            }
        }

        /** Additive: TMDB network and status for canonical shows. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `canonical_shows` ADD COLUMN `network` TEXT")
                db.execSQL("ALTER TABLE `canonical_shows` ADD COLUMN `status` TEXT")
            }
        }

        /** Additive: TMDB network logo for canonical shows (rows re-detailed once by CanonicalShowWorker). */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `canonical_shows` ADD COLUMN `networkLogo` TEXT")
            }
        }

        /** 23 → 25 directly: version 24's browse tables were added and removed again (see BrowseTablesMigration). */
        val MIGRATION_23_25 = object : Migration(23, 25) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                app.opentv.catalogue.BrowseTablesMigration.drop24to25.forEach(db::execSQL)
            }
        }

        private val writeExecutor: java.util.concurrent.Executor = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "jask-db-write").apply { isDaemon = true }
        }

        fun build(context: Context): OpenTvDatabase =
            Room.databaseBuilder(context, OpenTvDatabase::class.java, "opentv.db")
                // WAL keeps guide writes from blocking guide reads, so a background EPG
                // refresh cannot make the UI stutter on a slow TV box.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Writes (every Room write is a transaction) on background-priority threads: a catalogue or guide import
                // writes for seconds at a time, and at normal priority that competed with layout and drawing on a TV
                // stick's few cores. Reads stay on Room's default threads, so screens load at normal priority.
                .setTransactionExecutor(writeExecutor)
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_25, MIGRATION_24_25,
                )
                // v1 predates the retained migration chain; all versions >= 2 must migrate.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }

/** Keeps the existing call sites (`OpenTvDatabase.build(context)`, `OpenTvDatabase.MIGRATION_x_y`). */
fun OpenTvDatabase.Companion.build(context: Context): OpenTvDatabase = OpenTvDatabaseAndroid.build(context)
