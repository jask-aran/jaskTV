package app.opentv.ui.channels

import app.opentv.data.db.PhoneGuideHorizon
import app.opentv.data.model.Programme
import app.opentv.ui.ChannelsViewModel
import kotlinx.coroutines.flow.StateFlow

/** Keeps timeline previews and tests independent of Android services and provider inventory. */
interface PhoneGuideData {
    val phoneGuideVersion: StateFlow<Long>
    suspend fun phoneGuideHorizon(): PhoneGuideHorizon
    suspend fun phoneProgrammes(visibleRows: List<ChannelsViewModel.Row>, window: PhoneGuideTimeline.Window, nowNext: Boolean = false): Map<Any, List<Programme>>
}
