package com.airplay.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Mirroring video decode path.
 *
 * Clean re-implementation of the Sony `_ScreenRendererThread` (see
 * docs/SONY-SPEC.md), with the improvements we identified from the decompile:
 *
 *  - Amlogic hardware decoder selected by capability, H.264 **and HEVC**
 *    (Sony is H.264-only, capped 1080p; the dongle does 4K60 HEVC).
 *  - Asynchronous `MediaCodec.Callback` instead of Sony's 5ms poll loop.
 *  - `low-latency` + (optional) `tunneled-playback` — the standard-API
 *    equivalent of Sony's private `X-tv-output-path=OUTPUT_VIDEO_MAIN`.
 *  - Output rendered onto a SurfaceView overlay.
 *  - Frames are released on the sender's cadence via [FramePacer] (capture time
 *    + adaptive jitter delay, in nanoseconds — Sony appears to feed a
 *    microsecond pts to the ns `releaseOutputBufferAtTime` API).
 *
 * Feed order from the RTSP/stream layer:
 *   configure(csd)  -> start(surface) -> submitFrame(...) per NAL access unit.
 */
class VideoPipeline(
    /**
     * True when the output Surface's consumer ignores buffer timestamps (a
     * TextureView/SurfaceTexture acquires buffers as soon as they are queued):
     * the pipeline then holds each frame and releases it at its slot itself.
     * False for a SurfaceView, whose compositor schedules by timestamp.
     */
    private val selfTimedRelease: Boolean,
    private val preferLowLatency: Boolean = true,
) {
    enum class Codec(val mime: String, val hwNameHint: String) {
        H264(MediaFormat.MIMETYPE_VIDEO_AVC, "c2.amlogic.avc.decoder"),
        HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "c2.amlogic.hevc.decoder"),
    }

    // Frames arrive on the native network thread; buffers come back on the codec
    // thread; start/stop run on the main thread. Everything below is guarded by
    // [lock].
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null

    @Volatile private var width = 1920
    @Volatile private var height = 1080

    // One pacer per decoder session; only touched on that decoder's callback thread.
    @Volatile private var pacer = FramePacer()
    @Volatile private var codecHandler: Handler? = null
    private var shownOnTime = 0
    private var shownImmediately = 0

    val isRunning: Boolean get() = synchronized(lock) { codec != null }

    /**
     * csd-0/csd-1 carry SPS/PPS (H.264) or VPS/SPS/PPS (HEVC), start-code prefixed.
     * Empty csd is allowed: the AirPlay stream carries its parameter sets inline.
     * Frames queued before start (e.g. a replayed keyframe) are kept and decoded.
     */
    fun start(surface: Surface, codecType: Codec, w: Int, h: Int, csd0: ByteBuffer, csd1: ByteBuffer?) {
        releaseDecoder(clearPending = false)
        width = w; height = h
        val name = pickDecoder(codecType)
        val mc = MediaCodec.createByCodecName(name)
        val fmt = MediaFormat.createVideoFormat(codecType.mime, w, h).apply {
            // Mirror Sony's adaptive-playback + generous max dimensions so a
            // resolution change does not force a codec restart.
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(w, 3840))
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(h, 2160))
            setInteger("feature-adaptive-playback", 1)
            if (preferLowLatency && supportsLowLatency(name, codecType.mime)) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (csd0.remaining() > 0) setByteBuffer("csd-0", csd0)
            csd1?.takeIf { it.remaining() > 0 }?.let { setByteBuffer("csd-1", it) }
        }

        pacer = FramePacer()
        val ht = HandlerThread("video-codec").also { it.start() }
        codecHandler = Handler(ht.looper)
        mc.setCallback(callback, codecHandler)
        mc.configure(fmt, surface, null, 0)
        synchronized(lock) { codec = mc; thread = ht }
        mc.start()
        Log.i(TAG, "started $name ${w}x$h mime=${codecType.mime}")
    }

    /**
     * One access unit. [annexB] must already be Annex-B framed (start codes),
     * which is where the stream layer rewrites the AVCC 4-byte length prefix to
     * 00 00 00 01 (Sony does the same before queueInputBuffer).
     */
    /** [captureNs]: sender capture time on the System.nanoTime() clock. */
    fun submitFrame(annexB: ByteBuffer, captureNs: Long, isIdr: Boolean, isConfig: Boolean) {
        synchronized(lock) {
            // Nothing is consuming (no surface yet): don't grow without bound.
            // The bridge replays from the last keyframe whenever the screen attaches.
            if (codec == null && pending.size >= MAX_PENDING) pending.clear()
            pending.addLast(Frame(annexB, captureNs, isConfig))
            drainLocked()
        }
    }

    fun stop() = releaseDecoder(clearPending = true)

    private fun releaseDecoder(clearPending: Boolean) {
        val mc: MediaCodec?
        val ht: HandlerThread?
        synchronized(lock) {
            mc = codec; ht = thread
            codec = null; thread = null
            freeInputs.clear()  // indices belong to the old decoder
            if (clearPending) pending.clear()
        }
        // Release outside the lock: codec callbacks also take it.
        mc?.let { runCatching { it.stop() }; runCatching { it.release() } }
        ht?.quitSafely()
    }

    // --- internals ---------------------------------------------------------

    private data class Frame(val data: ByteBuffer, val captureNs: Long, val config: Boolean)

    private val pending = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            synchronized(lock) {
                if (mc !== codec) return  // late callback from a released decoder
                freeInputs.addLast(index)
                drainLocked()
            }
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) { if (mc !== codec) return }
            // presentationTimeUs carries the capture time (see drainLocked).
            val renderAtNs = pacer.scheduleFor(info.presentationTimeUs * 1_000, System.nanoTime())
            val handler = codecHandler
            when {
                renderAtNs == null -> runCatching { mc.releaseOutputBuffer(index, true) }
                selfTimedRelease && handler != null ->
                    // uptimeMillis and System.nanoTime() share CLOCK_MONOTONIC.
                    handler.postAtTime({
                        if (synchronized(lock) { mc === codec }) runCatching { mc.releaseOutputBuffer(index, true) }
                    }, renderAtNs / 1_000_000)
                else -> runCatching { mc.releaseOutputBuffer(index, renderAtNs) }
            }
            if (renderAtNs == null) shownImmediately++ else shownOnTime++
            if (shownOnTime + shownImmediately >= STATS_EVERY) {
                Log.i(TAG, "pacing: delay %.1f ms, %d scheduled, %d immediate".format(
                    pacer.delayMs, shownOnTime, shownImmediately))
                shownOnTime = 0; shownImmediately = 0
            }
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format: $format")
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
        }
    }

    /** Feeds queued access units into free decoder buffers. Caller holds [lock]. */
    private fun drainLocked() {
        val mc = codec ?: return
        try {
            while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
                val idx = freeInputs.removeFirst()
                val f = pending.removeFirst()
                val buf = mc.getInputBuffer(idx) ?: continue
                buf.clear(); buf.put(f.data)
                val flags = if (f.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                mc.queueInputBuffer(idx, 0, f.data.limit(), f.captureNs / 1_000, flags)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decoder not accepting input", e)
        }
    }

    private fun pickDecoder(c: Codec): String {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // Prefer the known Amlogic hw decoder, else any hw decoder for the mime.
        list.codecInfos.firstOrNull { it.name == c.hwNameHint && !it.isEncoder }?.let { return it.name }
        return list.codecInfos.firstOrNull { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals(c.mime, true) }
        }?.name ?: throw IllegalStateException("no hw decoder for ${c.mime}")
    }

    private fun supportsLowLatency(name: String, mime: String): Boolean = runCatching {
        val caps = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .first { it.name == name }.getCapabilitiesForType(mime)
        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "VideoPipeline"
        private const val MAX_PENDING = 600  // ~10 s at 60 fps
        private const val STATS_EVERY = 300  // log pacing stats every ~5 s of video
    }
}
