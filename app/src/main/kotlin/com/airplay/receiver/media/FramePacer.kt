package com.airplay.receiver.media

/**
 * Adaptive jitter buffer for mirrored video.
 *
 * Each decoded frame carries its capture time on the sender, mapped onto the
 * System.nanoTime() clock. Showing every frame a fixed delay after capture
 * reproduces the sender's cadence no matter how unevenly Wi-Fi delivered it —
 * releasing frames the instant they decode instead makes two land in one
 * refresh (one dropped) and none in the next, which reads as a "tick".
 *
 * The delay tracks the link: the 90th percentile of recent "decoded − captured"
 * lags plus a small margin (capped at 70 ms), so ~90% of frames are on time
 * while the added latency stays modest. It rises at
 * once when the link gets worse and eases back down slowly when it improves,
 * so the cadence never lurches.
 *
 * Confined to the decoder's callback thread; not thread-safe.
 */
class FramePacer {
    private val lags = LongArray(WINDOW)
    private var count = 0
    private var next = 0
    private var targetDelayNs = 0L
    private var delayNs = 0L
    private var sinceRecompute = 0

    /** Current presentation delay after capture, for diagnostics. */
    val delayMs: Double get() = delayNs / 1e6

    /**
     * Returns the System.nanoTime() at which to show a frame captured at
     * [captureNs], or null to show it immediately (it is due, late, or the pacer
     * has not learned the link yet).
     */
    fun scheduleFor(captureNs: Long, nowNs: Long): Long? {
        val lag = nowNs - captureNs
        // Implausible lags — a replayed keyframe from seconds ago, or a clock
        // that is not yet NTP-synced — are shown at once and kept out of the stats.
        if (lag < 0 || lag > MAX_TRACKED_LAG_NS) return null
        lags[next] = lag
        next = (next + 1) % WINDOW
        if (count < WINDOW) count++
        if (count < MIN_SAMPLES) return null

        if (targetDelayNs == 0L || ++sinceRecompute >= RECOMPUTE_EVERY) {
            sinceRecompute = 0
            targetDelayNs = percentileLag() + MARGIN_NS
        }
        delayNs = if (targetDelayNs >= delayNs) {
            targetDelayNs                                        // worse link: adapt now
        } else {
            delayNs - minOf(DECAY_STEP_NS, delayNs - targetDelayNs) // better link: ease down
        }

        val renderAt = captureNs + delayNs
        return if (renderAt - nowNs < MIN_SCHEDULE_AHEAD_NS) null else renderAt
    }

    private fun percentileLag(): Long {
        val sorted = lags.copyOf(count).also { it.sort() }
        return sorted[((count - 1) * PERCENTILE).toInt()].coerceAtMost(MAX_DELAY_NS)
    }

    private companion object {
        const val WINDOW = 180                       // ~3 s of frames at 60 fps
        const val MIN_SAMPLES = 20
        const val RECOMPUTE_EVERY = 30
        // Wi-Fi bursts can push the 95th-percentile lag past 100 ms, which feels
        // laggy on a mouse; 90% on time with a 70 ms ceiling is the better trade —
        // the rare later frame is simply shown on arrival.
        const val PERCENTILE = 0.90
        const val MARGIN_NS = 2_000_000L             // 2 ms headroom
        const val DECAY_STEP_NS = 250_000L           // ease down 0.25 ms per frame
        const val MAX_DELAY_NS = 70_000_000L         // never buffer more than 70 ms
        const val MAX_TRACKED_LAG_NS = 500_000_000L
        const val MIN_SCHEDULE_AHEAD_NS = 2_000_000L // closer than this: just show it
    }
}
