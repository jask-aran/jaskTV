/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop.mpv

import app.opentv.player.PlayerEngine
import app.opentv.player.PlayerEngineConfig
import app.opentv.player.PlayerEngineFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Desktop implementation of [PlayerEngineFactory].
 *
 * The engine owns its own event and render threads, so [scope] is accepted for contract symmetry
 * and is not used yet. [config]'s preview/DVR flags describe Media3 buffer tuning; libmpv has its
 * own cache options and ignores them until the desktop UI needs per-screen differences.
 */
class MpvPlayerEngineFactory : PlayerEngineFactory {
    override fun create(scope: CoroutineScope, config: PlayerEngineConfig): PlayerEngine =
        MpvPlayerEngine()
}
