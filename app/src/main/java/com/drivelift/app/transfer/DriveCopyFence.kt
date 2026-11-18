package com.drivelift.app.transfer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Keeps a user-directed Drive trash (issue #36) apart from the two things that rely on a Drive
 * copy staying where it is: the verifier marking it VERIFIED, and a phone-removal request that
 * relies on it. One process-wide instance ([com.drivelift.app.Graph]).
 *
 * - A trash reserves the copy before it reads the transfer rows and holds it until the trash has
 *   finished and any verified row was sent back. While it holds the copy, no removal request can
 *   include a file that relies on it, and the verifier cannot mark it VERIFIED.
 * - A copy trashed in this process is never marked VERIFIED again; the verifier's next read finds
 *   it trashed and uploads the file again.
 * - A removal request reserves the copies its files rely on from before the request is issued
 *   until Android has answered (including while the user is deciding). A trash refuses a reserved
 *   copy.
 *
 * Every check and the write it guards run under one lock, so there is no gap between them.
 */
class DriveCopyFence {
    private val mutex = Mutex()
    private val trashing = HashSet<String>()
    private val trashed = HashSet<String>()
    private val removing = HashSet<String>()

    /** Reserves [driveFileId] for a trash. False when an open removal request relies on it. */
    suspend fun beginTrash(driveFileId: String): Boolean = mutex.withLock {
        if (driveFileId in removing) false else {
            trashing += driveFileId
            true
        }
    }

    /** Releases a trash reservation; [trashedNow] when Drive confirmed the copy went to the trash. */
    suspend fun endTrash(driveFileId: String, trashedNow: Boolean) {
        mutex.withLock {
            trashing -= driveFileId
            if (trashedNow) trashed += driveFileId
        }
    }

    /**
     * Reserves the copies behind a removal request. [candidates] are (phone file, Drive copy) pairs;
     * returns the phone files that may be put in the request, leaving out any whose copy is being
     * trashed or was trashed.
     */
    suspend fun beginRemoval(candidates: List<Pair<String, String?>>): List<String> = mutex.withLock {
        candidates.filter { (_, copy) -> copy != null && copy !in trashing && copy !in trashed }
            .onEach { (_, copy) -> removing += copy!! }
            .map { it.first }
    }

    /** Android answered the removal request (or it was never shown). Only one is open at a time. */
    suspend fun endRemoval() {
        mutex.withLock { removing.clear() }
    }

    /** Runs [write] only while [driveFileId] is not being trashed and was not trashed. Null if refused. */
    suspend fun <T> unlessTrashed(driveFileId: String, write: suspend () -> T): T? = mutex.withLock {
        if (driveFileId in trashing || driveFileId in trashed) null else write()
    }
}

/** The verifier met a copy that is being, or was just, moved to the trash; it checks again later. */
class CopyBeingTrashedException :
    IOException("This Drive copy is being moved to the trash. It will be checked again.")
