package com.drivelift.app.transfer

/**
 * Exponential backoff with jitter: 2s, 4s, 8s ... capped at one minute.
 * There is no attempt limit on purpose. A transfer keeps retrying until the
 * underlying problem (usually the network) is gone.
 */
class Backoff(
    private val baseMs: Long = 2_000,
    private val maxMs: Long = 60_000,
    private val random: () -> Double = Math::random,
) {
    private var attempt = 0

    fun next(): Long {
        val ceiling = minOf(maxMs, baseMs shl minOf(attempt, 20))
        attempt++
        return ceiling / 2 + (random() * (ceiling / 2)).toLong()
    }

    fun reset() {
        attempt = 0
    }
}

/** Rolling upload speed over the last few seconds. */
class SpeedMeter(private val windowMs: Long = 5_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun record(nowMs: Long, totalBytes: Long): Long {
        samples.addLast(nowMs to totalBytes)
        while (samples.size > 2 && nowMs - samples.first().first > windowMs) samples.removeFirst()
        val (t0, b0) = samples.first()
        val elapsed = nowMs - t0
        return if (elapsed <= 0) 0 else (totalBytes - b0) * 1000 / elapsed
    }
}
