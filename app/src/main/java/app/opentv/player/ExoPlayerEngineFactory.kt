/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import app.opentv.core.AppSettings
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * Android [PlayerEngineFactory]: builds Media3-backed [PlayerController] instances.
 *
 * The platform swap point is [PlayerEngineFactory]. A desktop build supplies a VLC factory here
 * instead, and no screen changes.
 */
class ExoPlayerEngineFactory(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val settings: AppSettings,
) : PlayerEngineFactory {

    override fun create(
        scope: CoroutineScope,
        config: PlayerEngineConfig,
    ): PlayerEngine = PlayerController(
        context = context,
        scope = scope,
        httpClient = httpClient,
        subtitlesEnabled = config.subtitlesEnabled,
        preview = config.preview,
        dvr = config.dvr,
        liveRecording = config.liveRecording,
        smbDataSourceFactory = if (config.smb) SmbDataSource.Factory(settings) else null,
        growingDataSourceFactory =
            if (config.growingRecording) GrowingRecordingDataSource.Factory(context) else null,
    )
}
