package com.drivelift.app.feature.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.drivelift.app.feature.cockpit.activities.CallState
import com.drivelift.app.feature.cockpit.activities.CockpitLiveActivity
import com.drivelift.app.feature.cockpit.activities.MapsTurnDirection
import com.drivelift.app.ui.Tokens

/** Simulated live activities the Cockpit Hub can show in the island preview. */
enum class CockpitPreviewScenario(val label: String, val tag: String) {
    DEFAULT("Default", "cockpit-preview-default"),
    INCOMING_CALL("Incoming Call", "cockpit-preview-incoming-call"),
    ONGOING_CALL("Ongoing Call", "cockpit-preview-ongoing-call"),
    SCREEN_RECORDING("Screen Recording", "cockpit-preview-screen-recording"),
    NAVIGATION("Navigation", "cockpit-preview-navigation"),
    MULTI_SWAP("Multi-Activity Swap", "cockpit-preview-multi-swap"),
}

/** Synthetic activities for the preview chips. Nothing here touches a real notification or call. */
object CockpitPreviewActivities {
    private const val CALL_ID = "preview_call"
    private const val ONGOING_CALL_AGE_MS = 134_000L
    private const val RECORDING_AGE_MS = 6_000L

    fun incomingCall(nowMs: Long) = CockpitLiveActivity.CallActivity(
        id = CALL_ID,
        callerName = "Amina Khan",
        sourceLabel = "WhatsApp Audio",
        isVideo = false,
        state = CallState.INCOMING,
        startedAtMs = nowMs,
        packageName = "com.whatsapp",
        updatedAtMs = nowMs,
    )

    fun ongoingCall(nowMs: Long) = incomingCall(nowMs).copy(
        state = CallState.ONGOING,
        startedAtMs = nowMs - ONGOING_CALL_AGE_MS,
    )

    fun screenRecording(nowMs: Long) = CockpitLiveActivity.ScreenRecordingActivity(
        id = "preview_screen_recording",
        startedAtMs = nowMs - RECORDING_AGE_MS,
        updatedAtMs = nowMs,
    )

    fun navigation(nowMs: Long) = CockpitLiveActivity.MapsNavActivity(
        maneuverText = "North",
        distanceText = "90 ft",
        etaText = "12 min",
        turnDirection = MapsTurnDirection.STRAIGHT,
        roadText = "North",
        destinationText = "San Francisco",
        updatedAtMs = nowMs,
    )

    /** The queue the preview shows, already in priority order. [fallback] is the default preview pair. */
    fun queue(
        scenario: CockpitPreviewScenario,
        nowMs: Long,
        fallback: List<CockpitLiveActivity>,
    ): List<CockpitLiveActivity> = when (scenario) {
        CockpitPreviewScenario.DEFAULT -> fallback
        CockpitPreviewScenario.INCOMING_CALL -> listOf(incomingCall(nowMs))
        CockpitPreviewScenario.ONGOING_CALL -> listOf(ongoingCall(nowMs))
        CockpitPreviewScenario.SCREEN_RECORDING -> listOf(screenRecording(nowMs))
        CockpitPreviewScenario.NAVIGATION -> listOf(navigation(nowMs))
        CockpitPreviewScenario.MULTI_SWAP -> (
            listOf(ongoingCall(nowMs), screenRecording(nowMs), navigation(nowMs)) + fallback
            ).sortedBy { it.priority }
    }
}

/** One chip per simulated activity. Tapping the selected chip returns the preview to its default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CockpitPreviewScenarioChips(
    selected: CockpitPreviewScenario,
    onSelect: (CockpitPreviewScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cockpit-preview-chips"),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        CockpitPreviewScenario.entries
            .filter { it != CockpitPreviewScenario.DEFAULT }
            .forEach { scenario ->
                val isSelected = scenario == selected
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        onSelect(if (isSelected) CockpitPreviewScenario.DEFAULT else scenario)
                    },
                    label = { Text(scenario.label, maxLines = 1) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = Tokens.TouchTarget)
                        .testTag(scenario.tag),
                )
            }
    }
}
