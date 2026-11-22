package com.drivelift.app.feature.cockpit.activities

import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Arbitrates simultaneous live activities for the Dynamic Island:
 * - an ordered queue sorted by priority (Call, Screen recording, Navigation, Transfer, Timer,
 *   Media, WhatsApp, Idle) with an `activeIndex` pointing at the focused activity;
 * - the primary pill ([CockpitSlotState.primary]) and the satellite bubble
 *   ([CockpitSlotState.secondary], the next activity in the queue).
 *
 * Swiping left or right cycles the focus with wrap-around. A newly arriving activity that outranks
 * the one on screen takes focus; when it ends, the activity the user had focused is restored.
 * An incoming call expands the island on arrival and collapses it again once it connects or ends.
 * Soft-dismiss never touches the source notification.
 */
class CockpitStateCoordinator {
    private val lock = Any()
    private val activitiesById = LinkedHashMap<String, CockpitLiveActivity>()
    private val softDismissedIds = LinkedHashSet<String>()
    private var currentPrefs = CockpitPrefsState()
    private var manualFocusId: String? = null
    private var restoreFocusId: String? = null
    private var autoFocusedId: String? = null
    private var pendingSwapDirection = 0
    private var expanded = false
    private var inlineReplyOpen = false
    private val tripTracker = NavigationTripTracker()

    private val _slotState = MutableStateFlow(CockpitSlotState())
    val slotState: StateFlow<CockpitSlotState> = _slotState.asStateFlow()

    fun applyPrefs(prefs: CockpitPrefsState) {
        synchronized(lock) {
            currentPrefs = prefs
            recomputeLocked()
        }
    }

    fun upsertActivity(incoming: CockpitLiveActivity) {
        if (incoming is CockpitLiveActivity.IdleQuickDockActivity) return
        synchronized(lock) {
            val activity = if (incoming is CockpitLiveActivity.MapsNavActivity) withTripProgress(incoming) else incoming
            val previous = activitiesById[activity.id]
            val isNew = previous == null
            val currentPrimaryPriority = _slotState.value.primary.priority
            activitiesById[activity.id] = activity
            if (isNew && activity.priority < currentPrimaryPriority) {
                // A higher priority activity takes focus. Remember a focus the user chose by hand
                // so it comes back when the newcomer ends.
                if (manualFocusId != null) {
                    restoreFocusId = manualFocusId
                    autoFocusedId = activity.id
                }
                manualFocusId = null
            }
            if (activity is CockpitLiveActivity.CallActivity) {
                val wasRinging = (previous as? CockpitLiveActivity.CallActivity)?.state == CallState.INCOMING
                if (activity.state == CallState.INCOMING && !wasRinging) expanded = true
                if (activity.state == CallState.ONGOING && wasRinging) {
                    expanded = false
                    inlineReplyOpen = false
                }
            }
            recomputeLocked()
        }
    }

    fun removeActivity(id: String) {
        synchronized(lock) {
            removeIdsLocked(listOf(id))
            recomputeLocked()
        }
    }

    fun removeByNotificationKey(notificationKey: String) {
        synchronized(lock) {
            val idsToRemove = activitiesById.values.filter { activity ->
                when (activity) {
                    is CockpitLiveActivity.CallActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.ScreenRecordingActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.TimerRecordingActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.MediaActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.MapsNavActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.TransferActivity,
                    is CockpitLiveActivity.IdleQuickDockActivity -> false
                }
            }.map { it.id }
            if (idsToRemove.isNotEmpty()) {
                removeIdsLocked(idsToRemove)
                recomputeLocked()
            }
        }
    }

    /** Hides [id] from the Dynamic Island without closing the source app or cancelling its notification. */
    fun softDismiss(id: String) {
        if (id == CockpitLiveActivity.ID_IDLE) return
        synchronized(lock) {
            if (id in activitiesById) {
                softDismissedIds.add(id)
                if (manualFocusId == id) manualFocusId = null
                inlineReplyOpen = false
                recomputeLocked()
            }
        }
    }

    fun restoreDismissed(id: String) {
        synchronized(lock) {
            if (softDismissedIds.remove(id)) {
                manualFocusId = null
                recomputeLocked()
            }
        }
    }

    fun restoreAllDismissed() {
        synchronized(lock) {
            if (softDismissedIds.isNotEmpty()) {
                softDismissedIds.clear()
                manualFocusId = null
                recomputeLocked()
            }
        }
    }

    /** Swipe left: focus the next activity in the queue, wrapping from the last back to the first. */
    fun cycleNext() {
        synchronized(lock) {
            if (!cycleLocked(step = 1)) return
            recomputeLocked()
        }
    }

    /** Swipe right: focus the previous activity in the queue, wrapping from the first to the last. */
    fun cyclePrevious() {
        synchronized(lock) {
            if (!cycleLocked(step = -1)) return
            recomputeLocked()
        }
    }

    fun focusActivity(id: String) {
        synchronized(lock) {
            if (id in activitiesById && id !in softDismissedIds) {
                clearRestoreLocked()
                manualFocusId = id
                pendingSwapDirection = 1
                recomputeLocked()
            }
        }
    }

    fun setExpanded(isExpanded: Boolean) {
        synchronized(lock) {
            expanded = isExpanded
            if (!isExpanded) inlineReplyOpen = false
            recomputeLocked()
        }
    }

    fun setInlineReplyOpen(open: Boolean) {
        synchronized(lock) {
            if (open) expanded = true
            inlineReplyOpen = open
            recomputeLocked()
        }
    }

    fun clearAllForTest() {
        synchronized(lock) {
            activitiesById.clear()
            tripTracker.reset()
            softDismissedIds.clear()
            manualFocusId = null
            clearRestoreLocked()
            pendingSwapDirection = 0
            expanded = false
            inlineReplyOpen = false
            recomputeLocked()
        }
    }

    /** Adds how far the driver has come, measured from the first distance seen in this session. */
    private fun withTripProgress(nav: CockpitLiveActivity.MapsNavActivity): CockpitLiveActivity.MapsNavActivity {
        val routeKey = "${nav.packageName}|${nav.destinationText}|${nav.tripUsesMiles}"
        val progress = tripTracker.update(nav.remainingTripKm, routeKey)
        return nav.copy(droveTripKm = progress?.droveKm)
    }

    private fun clearRestoreLocked() {
        restoreFocusId = null
        autoFocusedId = null
    }

    private fun cycleLocked(step: Int): Boolean {
        val ordered = sortedVisibleLocked()
        if (ordered.size <= 1) return false
        val currentIdx = manualFocusId?.let { id -> ordered.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: 0
        val nextIdx = ((currentIdx + step) % ordered.size + ordered.size) % ordered.size
        clearRestoreLocked()
        manualFocusId = ordered[nextIdx].id
        inlineReplyOpen = false
        pendingSwapDirection = if (step >= 0) 1 else -1
        return true
    }

    private fun removeIdsLocked(ids: List<String>) {
        var removedRingingCall = false
        for (id in ids) {
            val removed = activitiesById.remove(id)
            if (removed is CockpitLiveActivity.MapsNavActivity) tripTracker.reset()
            if ((removed as? CockpitLiveActivity.CallActivity)?.state == CallState.INCOMING) {
                removedRingingCall = true
            }
            softDismissedIds.remove(id)
            if (manualFocusId == id) manualFocusId = null
            if (autoFocusedId == id) {
                // The activity that took focus is gone: go back to what the user was looking at.
                val restore = restoreFocusId
                if (restore != null && restore in activitiesById && restore !in softDismissedIds) {
                    manualFocusId = restore
                }
                clearRestoreLocked()
            } else if (restoreFocusId == id) {
                clearRestoreLocked()
            }
        }
        if (removedRingingCall && activitiesById.values.none { isRingingCall(it) }) {
            expanded = false
            inlineReplyOpen = false
        }
    }

    private fun isRingingCall(activity: CockpitLiveActivity): Boolean =
        (activity as? CockpitLiveActivity.CallActivity)?.state == CallState.INCOMING

    private fun sortedVisibleLocked(): List<CockpitLiveActivity> {
        return activitiesById.values
            .filter { activity ->
                val categoryEnabled = activity.category?.let { currentPrefs.isCategoryEnabled(it) } ?: true
                categoryEnabled && activity.id !in softDismissedIds
            }
            .sortedWith(
                compareBy<CockpitLiveActivity> { it.priority }
                    .thenByDescending { it.updatedAtMs }
                    .thenBy { it.id },
            )
    }

    private fun recomputeLocked() {
        val swapDirection = pendingSwapDirection
        pendingSwapDirection = 0
        val visible = sortedVisibleLocked()
        val dismissedEnabledCount = activitiesById.values.count { activity ->
            val categoryEnabled = activity.category?.let { currentPrefs.isCategoryEnabled(it) } ?: true
            categoryEnabled && activity.id in softDismissedIds
        }

        if (visible.isEmpty()) {
            manualFocusId = null
            _slotState.value = CockpitSlotState(
                primary = CockpitLiveActivity.IdleQuickDockActivity(),
                secondary = null,
                visibleQueue = emptyList(),
                dismissedCount = dismissedEnabledCount,
                isExpanded = expanded,
                inlineReplyOpen = false,
                activeIndex = 0,
                swapDirection = 0,
            )
            return
        }

        val focusedIndex = manualFocusId?.let { id -> visible.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: 0
        if (focusedIndex == 0) {
            manualFocusId = null
        }

        _slotState.value = CockpitSlotState(
            primary = visible[focusedIndex],
            secondary = if (visible.size > 1) visible[(focusedIndex + 1) % visible.size] else null,
            visibleQueue = visible,
            dismissedCount = dismissedEnabledCount,
            isExpanded = expanded,
            inlineReplyOpen = inlineReplyOpen,
            activeIndex = focusedIndex,
            swapDirection = swapDirection,
        )
    }
}
