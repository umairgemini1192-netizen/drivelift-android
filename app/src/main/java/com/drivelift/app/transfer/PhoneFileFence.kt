package com.drivelift.app.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a phone trash request apart from what relies on a phone file staying (issues #36, #42):
 * a transfer being queued for it, an open removal request, and the copy a duplicate cleanup
 * keeps. One process-wide instance ([com.drivelift.app.Graph]). Phone files are named by their
 * MediaStore URI.
 *
 * - A duplicate cleanup reserves, in one step under the lock and right before Android is asked,
 *   the copies it sends ("trashing") and the copy each group keeps ("keeping"). In that step it
 *   reads the transfer rows and the phone listing again: a copy a transfer now holds is left out,
 *   and a group whose kept copy is gone, changed size, is in an open removal request or is being
 *   trashed is refused whole. The reservation lasts until Android has answered (including while
 *   the user is deciding), so nothing that happens after the check can undercut it.
 * - Queuing a transfer and retrying failed ones (the only ways a file comes to be held by a
 *   transfer) run under the same lock and leave out a file that is being trashed.
 * - A removal request (verified files) reserves its files ("removing") until Android answered,
 *   leaving out kept copies and files being trashed.
 * - Every phone trash request leaves out kept copies and reserves what it sends.
 *
 * What happens outside DriveLift (another app editing or deleting a file) cannot be fenced; the
 * re-read right before the request is the last point DriveLift can check.
 */
class PhoneFileFence {
    private val mutex = Mutex()
    private val lock = Any()
    private val trashing = HashSet<String>()
    private val keeping = HashSet<String>()
    private val removing = HashSet<String>()

    /** One duplicate group in a cleanup: the copy that stays, the copies to trash, and their size. */
    data class Group(val keep: String, val trash: List<String>, val size: Long)

    /** What a cleanup reserved: released with [endCleanup] once Android answered. */
    data class Reservation(val trash: List<String>, val keep: List<String>) {
        val isEmpty: Boolean get() = trash.isEmpty()
    }

    /**
     * Runs [enqueue] under the lock with the files that must not be queued now (being trashed).
     * No cleanup can check or reserve files while it runs.
     */
    suspend fun <T> whileEnqueueing(enqueue: suspend (blocked: Set<String>) -> T): T =
        mutex.withLock { enqueue(synchronized(lock) { HashSet(trashing) }) }

    /**
     * Checks and reserves a duplicate cleanup in one step under the lock. [held] returns which of
     * the given files a transfer holds now; [present] returns the phone listing now (file to
     * size). Groups that cannot keep their copy are refused whole.
     */
    suspend fun beginCleanup(
        groups: List<Group>,
        held: suspend (List<String>) -> Set<String>,
        present: suspend () -> Map<String, Long>,
    ): Reservation = mutex.withLock {
        val heldNow = held(groups.flatMap { it.trash })
        val listed = present()
        synchronized(lock) {
            val trash = ArrayList<String>()
            val keep = ArrayList<String>()
            for (g in groups) {
                // A transfer may hold the kept copy: it stays on the phone either way.
                val keeperStays = listed[g.keep] == g.size && g.keep !in removing && g.keep !in trashing
                if (!keeperStays) continue
                val going = g.trash.filter { uri ->
                    uri != g.keep && uri !in heldNow && listed[uri] == g.size &&
                        uri !in removing && uri !in keeping && uri !in trashing
                }
                if (going.isEmpty()) continue
                trash += going
                keep += g.keep
            }
            trashing += trash
            keeping += keep
            Reservation(trash, keep)
        }
    }

    /** Android answered the cleanup's request, or it was never sent. */
    fun endCleanup(reservation: Reservation) {
        synchronized(lock) {
            trashing -= reservation.trash.toSet()
            keeping -= reservation.keep.toSet()
        }
    }

    /** A phone trash request is about to be sent: leaves out kept copies, reserves the rest. */
    fun beginTrash(uris: List<String>): List<String> = synchronized(lock) {
        uris.filter { it !in keeping }.also { trashing += it }
    }

    /** Android answered a phone trash request (or it was never shown). */
    fun endTrash(uris: List<String>) {
        synchronized(lock) { trashing -= uris.toSet() }
    }

    /** A removal request is about to be built: leaves out kept copies and files being trashed. */
    fun beginRemoval(uris: List<String>): List<String> = synchronized(lock) {
        uris.filter { it !in keeping && it !in trashing }.also { removing += it }
    }

    /** Android answered the removal request (or it was never shown). Only one is open at a time. */
    fun endRemoval() {
        synchronized(lock) { removing.clear() }
    }
}
