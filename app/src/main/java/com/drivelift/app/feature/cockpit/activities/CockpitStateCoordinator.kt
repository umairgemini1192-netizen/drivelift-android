package com.drivelift.app.feature.cockpit.activities

import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Arbitrates simultaneous live activities into up to 2 Island slots:
 * - Primary Pill ([CockpitSlotState.primary])
 * - Detached Secondary Circular Bubble ([CockpitSlotState.secondary])
 *
 * Priority order: Transfer/Desktop Drop -> Timer/Stopwatch/Recording -> Google Maps -> WhatsApp -> Idle.
 * Supports non-destructive soft-dismiss & restore and swipe-cycling without touching source notifications.
 */
class CockpitStateCoordinator {
    private val lock = Any()
    private val activitiesById = LinkedHashMap<String, CockpitLiveActivity>()
    private val softDismissedIds = LinkedHashSet<String>()
    private var currentPrefs = CockpitPrefsState()
    private var manualFocusId: String? = null
    private var expanded = false
    private var inlineReplyOpen = false

    private val _slotState = MutableStateFlow(CockpitSlotState())
    val slotState: StateFlow<CockpitSlotState> = _slotState.asStateFlow()

    fun applyPrefs(prefs: CockpitPrefsState) {
        synchronized(lock) {
            currentPrefs = prefs
            recomputeLocked()
        }
    }

    fun upsertActivity(activity: CockpitLiveActivity) {
        if (activity is CockpitLiveActivity.IdleQuickDockActivity) return
        synchronized(lock) {
            val isNew = activity.id !in activitiesById
            val currentPrimaryPriority = _slotState.value.primary.priority
            activitiesById[activity.id] = activity
            if (isNew && activity.priority < currentPrimaryPriority) {
                manualFocusId = null
            }
            recomputeLocked()
        }
    }

    fun removeActivity(id: String) {
        synchronized(lock) {
            activitiesById.remove(id)
            softDismissedIds.remove(id)
            if (manualFocusId == id) manualFocusId = null
            recomputeLocked()
        }
    }

    fun removeByNotificationKey(notificationKey: String) {
        synchronized(lock) {
            val idsToRemove = activitiesById.values.filter { activity ->
                when (activity) {
                    is CockpitLiveActivity.TimerRecordingActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.MapsNavActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.WhatsAppAlertActivity -> activity.notificationKey == notificationKey
                    is CockpitLiveActivity.TransferActivity,
                    is CockpitLiveActivity.IdleQuickDockActivity -> false
                }
            }.map { it.id }
            for (id in idsToRemove) {
                activitiesById.remove(id)
                softDismissedIds.remove(id)
                if (manualFocusId == id) manualFocusId = null
            }
            if (idsToRemove.isNotEmpty()) recomputeLocked()
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

    fun cycleNext() {
        synchronized(lock) {
            val ordered = sortedVisibleLocked()
            if (ordered.size <= 1) return
            val currentIdx = manualFocusId?.let { id -> ordered.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 } ?: 0
            val nextIdx = (currentIdx + 1) % ordered.size
            manualFocusId = ordered[nextIdx].id
            inlineReplyOpen = false
            recomputeLocked()
        }
    }

    fun cyclePrevious() {
        synchronized(lock) {
            val ordered = sortedVisibleLocked()
            if (ordered.size <= 1) return
            val currentIdx = manualFocusId?.let { id -> ordered.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 } ?: 0
            val prevIdx = (currentIdx - 1 + ordered.size) % ordered.size
            manualFocusId = ordered[prevIdx].id
            inlineReplyOpen = false
            recomputeLocked()
        }
    }

    fun focusActivity(id: String) {
        synchronized(lock) {
            if (id in activitiesById && id !in softDismissedIds) {
                manualFocusId = id
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
            softDismissedIds.clear()
            manualFocusId = null
            expanded = false
            inlineReplyOpen = false
            recomputeLocked()
        }
    }

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
            )
            return
        }

        val focusedIndex = manualFocusId?.let { id -> visible.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: 0
        if (focusedIndex == 0) {
            manualFocusId = null
        }
        val rotated = List(visible.size) { offset ->
            visible[(focusedIndex + offset) % visible.size]
        }

        _slotState.value = CockpitSlotState(
            primary = rotated[0],
            secondary = rotated.getOrNull(1),
            visibleQueue = rotated,
            dismissedCount = dismissedEnabledCount,
            isExpanded = expanded,
            inlineReplyOpen = inlineReplyOpen,
        )
    }
}
