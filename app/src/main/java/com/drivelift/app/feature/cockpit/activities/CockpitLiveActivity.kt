package com.drivelift.app.feature.cockpit.activities

import android.graphics.Bitmap
import com.drivelift.app.feature.cockpit.data.CockpitActivityCategory

enum class MapsTurnDirection {
    STRAIGHT,
    LEFT,
    RIGHT,
    UTURN,
    ARRIVE,
}

/** Whether a call is still ringing or already connected. Call screening is treated as ringing. */
enum class CallState {
    INCOMING,
    ONGOING,
}

/**
 * Typed live activities surfaced inside the Dynamic Island pill and detached secondary bubble.
 * Priority ordering (lower number wins the primary slot):
 * 0. Call (cellular and VoIP)
 * 1. Screen recording
 * 2. Turn-by-turn navigation
 * 3. DriveLift active transfer / Desktop Drop
 * 4. Timer / stopwatch / voice recording
 * 5. Media
 * 6. WhatsApp message alert
 * 7. Idle / personal quick dock
 */
sealed interface CockpitLiveActivity {
    val id: String
    val priority: Int
    val updatedAtMs: Long
    val category: CockpitActivityCategory?

    data class CallActivity(
        override val id: String,
        val callerName: String,
        val sourceLabel: String,
        val isVideo: Boolean,
        val state: CallState,
        /** Wall-clock start of the connected call, used for the live MM:SS duration. */
        val startedAtMs: Long,
        val packageName: String,
        val notificationKey: String? = null,
        val avatar: Bitmap? = null,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_CALL
        override val category: CockpitActivityCategory? = null
    }

    data class ScreenRecordingActivity(
        override val id: String,
        /** Wall-clock start of the recording, used for the live MM:SS duration. */
        val startedAtMs: Long,
        val notificationKey: String? = null,
        val packageName: String = PKG_SYSTEM_UI,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_SCREEN_RECORDING
        override val category: CockpitActivityCategory = CockpitActivityCategory.TIMERS_AND_RECORDING
    }

    data class TransferActivity(
        override val id: String = ID_TRANSFER,
        val title: String,
        val subtitle: String,
        val speedText: String,
        val progressFraction: Float?,
        val isDesktopDrop: Boolean,
        val canStop: Boolean,
        val activeCount: Int = 1,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_TRANSFER
        override val category: CockpitActivityCategory = CockpitActivityCategory.TRANSFERS_AND_DROP
    }

    data class TimerRecordingActivity(
        override val id: String,
        val label: String,
        val timeText: String,
        val isRecording: Boolean,
        val packageName: String,
        val chronometerBaseMs: Long? = null,
        val isCountDown: Boolean = false,
        val notificationKey: String? = null,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_TIMER
        override val category: CockpitActivityCategory = CockpitActivityCategory.TIMERS_AND_RECORDING
    }

    data class MediaActivity(
        override val id: String,
        val title: String,
        val artist: String,
        val packageName: String,
        val notificationKey: String? = null,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_MEDIA
        override val category: CockpitActivityCategory? = null
    }

    data class MapsNavActivity(
        override val id: String = ID_MAPS,
        val maneuverText: String,
        val distanceText: String,
        val etaText: String,
        val turnDirection: MapsTurnDirection = MapsTurnDirection.STRAIGHT,
        val packageName: String = MAPS_PACKAGE,
        val notificationKey: String? = null,
        /** Primary road or compass direction shown under the distance, for example "North". */
        val roadText: String = "",
        /** Muted destination line, for example a city or street name. */
        val destinationText: String = "",
        /** Maneuver picture taken from the navigation notification, when it provides one. */
        val maneuverIcon: Bitmap? = null,
        /** Distance left on the whole trip, in kilometres, when the notification names one. */
        val remainingTripKm: Double? = null,
        /** Distance already driven on this trip, in kilometres. Filled in by the coordinator. */
        val droveTripKm: Double? = null,
        /** Live driving speed in km/h from passive GPS or notification telemetry, when available. */
        val speedKmh: Int? = null,
        /** True when the source app shows miles, so the footer uses the same unit. */
        val tripUsesMiles: Boolean = false,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_NAVIGATION
        override val category: CockpitActivityCategory = CockpitActivityCategory.MAPS_NAVIGATION
    }

    data class WhatsAppAlertActivity(
        override val id: String,
        val senderName: String,
        val messageSnippet: String,
        val notificationKey: String,
        val canInlineReply: Boolean,
        val isBusiness: Boolean = false,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_WHATSAPP
        override val category: CockpitActivityCategory = CockpitActivityCategory.WHATSAPP_ALERTS
    }

    data class IdleQuickDockActivity(
        override val id: String = ID_IDLE,
        val subtitle: String = "Quick Dock",
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_IDLE
        override val category: CockpitActivityCategory? = null
    }

    companion object {
        const val PRIORITY_CALL = 0
        const val PRIORITY_SCREEN_RECORDING = 1
        const val PRIORITY_NAVIGATION = 2
        const val PRIORITY_TRANSFER = 3
        const val PRIORITY_TIMER = 4
        const val PRIORITY_MEDIA = 5
        const val PRIORITY_WHATSAPP = 6
        const val PRIORITY_IDLE = 7

        const val ID_TRANSFER = "drivelift_transfer"
        const val ID_DESKTOP_DROP = "drivelift_desktop_drop"
        const val ID_MAPS = "google_maps_nav"
        const val ID_IDLE = "idle_quick_dock"
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val PKG_SYSTEM_UI = "com.android.systemui"
    }
}

/**
 * Output of the Dynamic Island coordinator:
 * - [visibleQueue] is every visible activity sorted by priority; [activeIndex] is the focused one.
 * - [primary] renders in the main pill around the cutout;
 * - [secondary] renders in the satellite circular bubble when >= 2 live activities are active.
 * - [swapDirection] is +1 after a swipe left or a satellite tap (next), -1 after a swipe right
 *   (previous) and 0 for every other change, so the UI can slide the right way.
 */
data class CockpitSlotState(
    val primary: CockpitLiveActivity = CockpitLiveActivity.IdleQuickDockActivity(),
    val secondary: CockpitLiveActivity? = null,
    val visibleQueue: List<CockpitLiveActivity> = emptyList(),
    val dismissedCount: Int = 0,
    val isExpanded: Boolean = false,
    val inlineReplyOpen: Boolean = false,
    val activeIndex: Int = 0,
    val swapDirection: Int = 0,
)
