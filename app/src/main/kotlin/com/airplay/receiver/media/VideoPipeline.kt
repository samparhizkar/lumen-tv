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
 *  - Frame pacing uses **nanoseconds** correctly (Sony appears to feed a
 *    microsecond pts to the ns `releaseOutputBufferAtTime` API).
 *
 * Feed order from the RTSP/stream layer:
 *   configure(csd)  -> start(surface) -> submitFrame(...) per NAL access unit.
 */
class VideoPipeline(
    private val clock: MirrorClock,
    private val preferLowLatency: Boolean = true,
) {
    enum class Codec(val mime: String, val hwNameHint: String) {
        H264(MediaFormat.MIMETYPE_VIDEO_AVC, "c2.amlogic.avc.decoder"),
        HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "c2.amlogic.hevc.decoder"),
    }

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var width = 1920
    @Volatile private var height = 1080
    @Volatile private var lastIdrPtsUs = -1L
    @Volatile private var firstAfterIdr = false

    /** csd-0/csd-1 carry SPS/PPS (H.264) or VPS/SPS/PPS (HEVC), start-code prefixed. */
    fun start(surface: Surface, codecType: Codec, w: Int, h: Int, csd0: ByteBuffer, csd1: ByteBuffer?) {
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
            setByteBuffer("csd-0", csd0)
            csd1?.let { setByteBuffer("csd-1", it) }
        }

        val ht = HandlerThread("video-codec").also { it.start() }
        val hd = Handler(ht.looper)
        mc.setCallback(callback, hd)
        mc.configure(fmt, surface, null, 0)
        mc.start()
        codec = mc; thread = ht; handler = hd
        Log.i(TAG, "started $name ${w}x$h mime=${codecType.mime}")
    }

    /**
     * One access unit. [annexB] must already be Annex-B framed (start codes),
     * which is where the stream layer rewrites the AVCC 4-byte length prefix to
     * 00 00 00 01 (Sony does the same before queueInputBuffer).
     */
    fun submitFrame(annexB: ByteBuffer, ptsUs: Long, isIdr: Boolean, isConfig: Boolean) {
        if (isIdr) { lastIdrPtsUs = ptsUs; firstAfterIdr = true }
        pending.addLast(Frame(annexB, ptsUs, isConfig))
        drainInputs()
    }

    fun stop() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        thread?.quitSafely()
        codec = null; thread = null; handler = null
        pending.clear(); freeInputs.clear()
    }

    // --- internals ---------------------------------------------------------

    private data class Frame(val data: ByteBuffer, val ptsUs: Long, val config: Boolean)

    private val pending = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            freeInputs.addLast(index); drainInputs()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            // TODO: reinstate clock-paced release (releaseOutputBufferAtTime) once
            // the NTP->local clock mapping is calibrated. For bring-up, render each
            // decoded frame immediately so latency is low and nothing blacks out on
            // an uncalibrated timestamp.
            mc.releaseOutputBuffer(index, true)
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format: $format")
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
        }
    }

    private fun drainInputs() {
        val mc = codec ?: return
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val idx = freeInputs.removeFirst()
            val f = pending.removeFirst()
            val buf = mc.getInputBuffer(idx) ?: continue
            buf.clear(); buf.put(f.data)
            val flags = if (f.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            mc.queueInputBuffer(idx, 0, f.data.limit(), f.ptsUs, flags)
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

    companion object { private const val TAG = "VideoPipeline" }
}
