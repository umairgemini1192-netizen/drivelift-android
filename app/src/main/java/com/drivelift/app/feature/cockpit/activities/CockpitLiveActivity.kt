package com.drivelift.app.feature.cockpit.activities

import com.drivelift.app.feature.cockpit.data.CockpitActivityCategory

enum class MapsTurnDirection {
    STRAIGHT,
    LEFT,
    RIGHT,
    UTURN,
    ARRIVE,
}

/**
 * Typed live activities surfaced inside the Dynamic Island pill and detached secondary bubble.
 * Priority ordering matches DriveLift Cockpit specification:
 * 1. DriveLift Active Transfer / Desktop Drop
 * 2. Ongoing Timer / Stopwatch / Recording
 * 3. Google Maps Turn-by-Turn
 * 4. WhatsApp Live Alert
 * 5. Idle / Personal Quick Dock
 */
sealed interface CockpitLiveActivity {
    val id: String
    val priority: Int
    val updatedAtMs: Long
    val category: CockpitActivityCategory?

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

    data class MapsNavActivity(
        override val id: String = ID_MAPS,
        val maneuverText: String,
        val distanceText: String,
        val etaText: String,
        val turnDirection: MapsTurnDirection = MapsTurnDirection.STRAIGHT,
        val packageName: String = MAPS_PACKAGE,
        val notificationKey: String? = null,
        override val updatedAtMs: Long = 0L,
    ) : CockpitLiveActivity {
        override val priority: Int = PRIORITY_MAPS
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
        const val PRIORITY_TRANSFER = 1
        const val PRIORITY_TIMER = 2
        const val PRIORITY_MAPS = 3
        const val PRIORITY_WHATSAPP = 4
        const val PRIORITY_IDLE = 5

        const val ID_TRANSFER = "drivelift_transfer"
        const val ID_DESKTOP_DROP = "drivelift_desktop_drop"
        const val ID_MAPS = "google_maps_nav"
        const val ID_IDLE = "idle_quick_dock"
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}

/**
 * Output of the 2-slot Dynamic Island coordinator:
 * [primary] renders in the main pill around the cutout;
 * [secondary] renders in the detached circular bubble when >= 2 live activities are active.
 */
data class CockpitSlotState(
    val primary: CockpitLiveActivity = CockpitLiveActivity.IdleQuickDockActivity(),
    val secondary: CockpitLiveActivity? = null,
    val visibleQueue: List<CockpitLiveActivity> = emptyList(),
    val dismissedCount: Int = 0,
    val isExpanded: Boolean = false,
    val inlineReplyOpen: Boolean = false,
)
