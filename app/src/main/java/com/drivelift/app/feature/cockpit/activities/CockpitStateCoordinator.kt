package com.drivelift.app.feature.cockpit.activities

import com.drivelift.app.feature.cockpit.alerts.CockpitSystemAlert
import com.drivelift.app.feature.cockpit.data.CockpitPrefsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 *
 * A transient system alert ([setSystemAlert]) is layered on top of the queue, never inside it. It
 * is shown only while the island is collapsed, switched on in the prefs and no call is ringing, and
 * it leaves the queue, focus and expanded state untouched, so the island returns to the live
 * activity (or the idle island) the moment the alert ends.
 */
class CockpitStateCoordinator(
    private val scope: CoroutineScope? = null,
    private val emptyStateDebounceMs: Long = EMPTY_STATE_DEBOUNCE_MS,
    private val chatAutoExpireMs: Long = TRANSIENT_CHAT_AUTO_EXPIRE_MS,
) {
    private val lock = Any()
    private val activitiesById = LinkedHashMap<String, CockpitLiveActivity>()
    private val softDismissedIds = LinkedHashSet<String>()
    private val pendingEmptyRemovalIds = LinkedHashSet<String>()
    private val chatExpiryJobs = LinkedHashMap<String, Job>()
    private var currentPrefs = CockpitPrefsState()
    private var manualFocusId: String? = null
    private var restoreFocusId: String? = null
    private var autoFocusedId: String? = null
    private var pendingSwapDirection = 0
    private var expanded = false
    private var inlineReplyOpen = false
    private var autoCollapseJob: Job? = null
    private var emptyDebounceJob: Job? = null
    private var latestNavSpeedKmh: Int? = null
    private val tripTracker = NavigationTripTracker()
    private var systemAlert: CockpitSystemAlert? = null

    private val _slotState = MutableStateFlow(CockpitSlotState())
    val slotState: StateFlow<CockpitSlotState> = _slotState.asStateFlow()

    private val _activeSystemAlert = MutableStateFlow<CockpitSystemAlert?>(null)

    /** The system alert the island is showing right now, or null when none is shown. */
    val activeSystemAlert: StateFlow<CockpitSystemAlert?> = _activeSystemAlert.asStateFlow()

    /** Sets the alert raised by the system alerts monitor, or clears it with null. */
    fun setSystemAlert(alert: CockpitSystemAlert?) {
        synchronized(lock) {
            systemAlert = alert
            recomputeLocked()
        }
    }

    /** Cancels any pending auto-collapse timer when the user touches or interacts with the Island. */
    fun onUserInteraction() {
        synchronized(lock) {
            cancelAutoCollapseLocked()
        }
    }

    /** Schedules an automatic collapse after [delayMs]; cancelled by any touch, expansion, or update. */
    fun scheduleAutoCollapse(delayMs: Long) {
        synchronized(lock) {
            cancelAutoCollapseLocked()
            val activeScope = scope ?: return
            if (delayMs <= 0L || !expanded || inlineReplyOpen) return
            autoCollapseJob = activeScope.launch {
                delay(delayMs)
                synchronized(lock) {
                    autoCollapseJob = null
                    if (expanded && !inlineReplyOpen) {
                        expanded = false
                        inlineReplyOpen = false
                        recomputeLocked()
                    }
                }
            }
        }
    }

    /** Cancels any pending auto-collapse coroutine timer immediately. */
    fun cancelAutoCollapse() {
        synchronized(lock) {
            cancelAutoCollapseLocked()
        }
    }

    internal fun hasPendingAutoCollapseForTest(): Boolean = synchronized(lock) {
        autoCollapseJob?.isActive == true
    }

    internal fun hasPendingEmptyDebounceForTest(): Boolean = synchronized(lock) {
        emptyDebounceJob?.isActive == true
    }

    fun applyPrefs(prefs: CockpitPrefsState) {
        synchronized(lock) {
            currentPrefs = prefs
            recomputeLocked()
        }
    }

    fun upsertActivity(incoming: CockpitLiveActivity) {
        if (incoming is CockpitLiveActivity.IdleQuickDockActivity) return
        synchronized(lock) {
            cancelAutoCollapseLocked()
            if (pendingEmptyRemovalIds.isNotEmpty()) {
                val staleIds = pendingEmptyRemovalIds.filterNot { it == incoming.id }
                cancelEmptyDebounceLocked()
                if (staleIds.isNotEmpty()) {
                    removeIdsLocked(staleIds)
                }
            }
            val activity = if (incoming is CockpitLiveActivity.MapsNavActivity) {
                val mergedSpeed = incoming.speedKmh ?: latestNavSpeedKmh
                if (incoming.speedKmh != null) latestNavSpeedKmh = incoming.speedKmh
                withTripProgress(incoming.copy(speedKmh = mergedSpeed))
            } else {
                incoming
            }
            val previous = activitiesById[activity.id]
            val isNew = previous == null
            val currentPrimaryPriority = _slotState.value.primary.priority
            activitiesById[activity.id] = activity
            if (activity is CockpitLiveActivity.WhatsAppAlertActivity) {
                softDismissedIds.remove(activity.id)
                scheduleChatAutoExpireLocked(activity.id)
            }
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

    /** Updates live GPS speed (km/h) for an active navigation session without touching other fields. */
    fun updateNavigationSpeed(speedKmh: Int?) {
        synchronized(lock) {
            latestNavSpeedKmh = speedKmh
            val current = activitiesById[CockpitLiveActivity.ID_MAPS] as? CockpitLiveActivity.MapsNavActivity
                ?: return
            if (current.speedKmh == speedKmh) return
            activitiesById[current.id] = current.copy(speedKmh = speedKmh)
            recomputeLocked()
        }
    }

    fun removeActivity(id: String) {
        synchronized(lock) {
            removeOrDebounceEmptyLocked(listOf(id))
        }
    }

    /**
     * Immediately removes a transient chat alert (triggered by swipe-up, compact X dismiss, or
     * auto-expiry) so messages never stick in the island or linger in Restore.
     */
    fun dismissTransientAlert(id: String) {
        if (id == CockpitLiveActivity.ID_IDLE) return
        synchronized(lock) {
            cancelChatExpiryLocked(id)
            pendingEmptyRemovalIds.remove(id)
            val removed = activitiesById[id] ?: return
            val wasOnlyVisible = sortedVisibleLocked().size <= 1 && sortedVisibleLocked().firstOrNull()?.id == id
            removeIdsLocked(listOf(id))
            if (removed is CockpitLiveActivity.WhatsAppAlertActivity && wasOnlyVisible) {
                expanded = false
                inlineReplyOpen = false
            }
            recomputeLocked()
        }
    }

    /** Removes every notification-backed activity (called if NotificationListenerService disconnects). */
    fun clearNotificationBackedActivities() {
        synchronized(lock) {
            val ids = activitiesById.values.filter { activity ->
                when (activity) {
                    is CockpitLiveActivity.CallActivity -> activity.notificationKey != null
                    is CockpitLiveActivity.ScreenRecordingActivity -> activity.notificationKey != null
                    is CockpitLiveActivity.TimerRecordingActivity -> activity.notificationKey != null
                    is CockpitLiveActivity.MediaActivity -> activity.notificationKey != null
                    is CockpitLiveActivity.MapsNavActivity -> activity.notificationKey != null
                    is CockpitLiveActivity.WhatsAppAlertActivity -> true
                    is CockpitLiveActivity.TransferActivity,
                    is CockpitLiveActivity.IdleQuickDockActivity -> false
                }
            }.map { it.id }
            if (ids.isEmpty()) return
            cancelEmptyDebounceLocked()
            removeIdsLocked(ids)
            recomputeLocked()
        }
    }

    fun removeByNotificationKey(notificationKey: String) {
        synchronized(lock) {
            val matched = activitiesById.values.filter { activity ->
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
            }
            if (matched.isEmpty()) return
            val chatIds = matched.filterIsInstance<CockpitLiveActivity.WhatsAppAlertActivity>().map { it.id }
            val otherIds = matched.filterNot { it is CockpitLiveActivity.WhatsAppAlertActivity }.map { it.id }
            if (chatIds.isNotEmpty()) {
                pendingEmptyRemovalIds.removeAll(chatIds.toSet())
                removeIdsLocked(chatIds)
                recomputeLocked()
            }
            if (otherIds.isNotEmpty()) {
                removeOrDebounceEmptyLocked(otherIds)
            }
        }
    }

    /** Hides [id] from the Dynamic Island without closing the source app or cancelling its notification. */
    fun softDismiss(id: String) {
        if (id == CockpitLiveActivity.ID_IDLE) return
        synchronized(lock) {
            val target = activitiesById[id] ?: return
            if (target is CockpitLiveActivity.WhatsAppAlertActivity) {
                dismissTransientAlert(id)
                return
            }
            softDismissedIds.add(id)
            if (manualFocusId == id) manualFocusId = null
            inlineReplyOpen = false
            recomputeLocked()
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
            cancelAutoCollapseLocked()
            if (!cycleLocked(step = 1)) return
            recomputeLocked()
        }
    }

    /** Swipe right: focus the previous activity in the queue, wrapping from the first to the last. */
    fun cyclePrevious() {
        synchronized(lock) {
            cancelAutoCollapseLocked()
            if (!cycleLocked(step = -1)) return
            recomputeLocked()
        }
    }

    fun focusActivity(id: String) {
        synchronized(lock) {
            cancelAutoCollapseLocked()
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
            cancelAutoCollapseLocked()
            expanded = isExpanded
            if (!isExpanded) inlineReplyOpen = false
            recomputeLocked()
        }
    }

    fun setInlineReplyOpen(open: Boolean) {
        synchronized(lock) {
            cancelAutoCollapseLocked()
            if (open) expanded = true
            inlineReplyOpen = open
            if (!open) {
                activitiesById.values
                    .filterIsInstance<CockpitLiveActivity.WhatsAppAlertActivity>()
                    .forEach { scheduleChatAutoExpireLocked(it.id) }
            }
            recomputeLocked()
        }
    }

    fun clearAllForTest() {
        synchronized(lock) {
            cancelAutoCollapseLocked()
            cancelEmptyDebounceLocked()
            chatExpiryJobs.values.forEach { it.cancel() }
            chatExpiryJobs.clear()
            activitiesById.clear()
            tripTracker.reset()
            latestNavSpeedKmh = null
            softDismissedIds.clear()
            manualFocusId = null
            clearRestoreLocked()
            pendingSwapDirection = 0
            expanded = false
            inlineReplyOpen = false
            systemAlert = null
            recomputeLocked()
        }
    }

    internal fun hasPendingChatExpiryForTest(id: String): Boolean = synchronized(lock) {
        chatExpiryJobs[id]?.isActive == true
    }

    private fun scheduleChatAutoExpireLocked(id: String) {
        cancelChatExpiryLocked(id)
        val activeScope = scope ?: return
        if (chatAutoExpireMs <= 0L) return
        chatExpiryJobs[id] = activeScope.launch {
            delay(chatAutoExpireMs)
            synchronized(lock) {
                chatExpiryJobs.remove(id)
                val stillPresent = activitiesById[id] as? CockpitLiveActivity.WhatsAppAlertActivity ?: return@synchronized
                if (inlineReplyOpen && _slotState.value.primary.id == stillPresent.id) {
                    // User is actively typing a reply: do not pull the card out from under them.
                    return@synchronized
                }
                val wasOnlyVisible = sortedVisibleLocked().size <= 1 && sortedVisibleLocked().firstOrNull()?.id == id
                pendingEmptyRemovalIds.remove(id)
                removeIdsLocked(listOf(id))
                if (wasOnlyVisible) {
                    expanded = false
                    inlineReplyOpen = false
                }
                recomputeLocked()
            }
        }
    }

    private fun cancelChatExpiryLocked(id: String) {
        chatExpiryJobs.remove(id)?.cancel()
    }

    private fun cancelAutoCollapseLocked() {
        autoCollapseJob?.cancel()
        autoCollapseJob = null
    }

    private fun cancelEmptyDebounceLocked() {
        emptyDebounceJob?.cancel()
        emptyDebounceJob = null
        pendingEmptyRemovalIds.clear()
    }

    private fun removeOrDebounceEmptyLocked(ids: List<String>) {
        val existingIds = ids.filter { it in activitiesById }
        if (existingIds.isEmpty()) return

        val activeScope = scope
        if (activeScope == null || emptyStateDebounceMs <= 0L) {
            removeIdsLocked(existingIds)
            recomputeLocked()
            return
        }

        val combinedPending = pendingEmptyRemovalIds + existingIds
        val remainingVisible = sortedVisibleLocked().filterNot { it.id in combinedPending }
        if (remainingVisible.isNotEmpty()) {
            // Queue is not becoming empty: apply immediately without delaying transition.
            pendingEmptyRemovalIds.removeAll(existingIds.toSet())
            removeIdsLocked(existingIds)
            recomputeLocked()
            return
        }

        // Queue would become empty: debounce for 500ms to ignore transient empty state emissions
        // during notification updates so the Island never suddenly closes mid-use.
        pendingEmptyRemovalIds.addAll(existingIds)
        emptyDebounceJob?.cancel()
        emptyDebounceJob = activeScope.launch {
            delay(emptyStateDebounceMs)
            synchronized(lock) {
                emptyDebounceJob = null
                if (pendingEmptyRemovalIds.isNotEmpty()) {
                    val toCommit = pendingEmptyRemovalIds.toList()
                    pendingEmptyRemovalIds.clear()
                    removeIdsLocked(toCommit)
                    recomputeLocked()
                }
            }
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
            cancelChatExpiryLocked(id)
            val removed = activitiesById.remove(id)
            if (removed is CockpitLiveActivity.MapsNavActivity) {
                tripTracker.reset()
                latestNavSpeedKmh = null
            }
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
        val surfacedAlert = systemAlert?.takeIf {
            currentPrefs.systemAlertsEnabled && !expanded && visible.none { activity -> isRingingCall(activity) }
        }
        _activeSystemAlert.value = surfacedAlert
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
                transientAlert = surfacedAlert,
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
            transientAlert = surfacedAlert,
        )
    }

    companion object {
        const val EMPTY_STATE_DEBOUNCE_MS = 500L
        const val TRANSIENT_CHAT_AUTO_EXPIRE_MS = 10_000L
    }
}
