package com.airplay.receiver.media

/**
 * Maps stream presentation timestamps (AirPlay RTP / NTP domain, microseconds)
 * to the local playback clock, and to `System.nanoTime()`-based release times
 * for `MediaCodec.releaseOutputBuffer(index, renderTimestampNs)`.
 *
 * Classic AirPlay uses the NTP-synchronized clock (see SONY-SPEC.md — the Sony
 * `AirTunesClock` forwards to an NTP impl when not in AirPlay-2 PTP mode). This
 * is intentionally the unprivileged path: no PTP, no ports 319/320.
 *
 * The real offset/skew estimate is fed by the RTSP timing layer; until that is
 * wired, this is an identity mapping against the monotonic clock so the pipeline
 * runs end-to-end.
 */
class MirrorClock {
    @Volatile private var offsetUs: Long = 0      // streamPts + offset = localPlaybackUs
    @Volatile private var baseNanoAtEpoch: Long = System.nanoTime()
    @Volatile private var baseLocalUs: Long = nowMonotonicUs()

    /** Called by the timing layer when a new sync point arrives. */
    fun updateSync(streamPtsUs: Long, localPlaybackUs: Long) {
        offsetUs = localPlaybackUs - streamPtsUs
    }

    /** Current local playback time, microseconds. */
    fun nowUs(): Long = nowMonotonicUs()

    /** Stream pts (µs) → absolute System.nanoTime() domain for scheduled render. */
    fun ptsUsToSystemNanos(streamPtsUs: Long): Long {
        val localUs = streamPtsUs + offsetUs
        val deltaUs = localUs - baseLocalUs
        return baseNanoAtEpoch + deltaUs * 1_000L
    }

    private fun nowMonotonicUs(): Long = System.nanoTime() / 1_000L
}
