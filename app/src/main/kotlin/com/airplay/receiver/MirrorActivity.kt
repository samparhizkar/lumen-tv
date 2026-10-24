package com.airplay.receiver

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.airplay.receiver.media.AudioPipeline
import com.airplay.receiver.media.MirrorClock
import com.airplay.receiver.media.VideoPipeline
import com.airplay.receiver.service.AirPlayService
import com.airplay.receiver.service.MirrorActivityBridge
import java.nio.ByteBuffer

/**
 * Full-screen mirroring surface. Video goes to a SurfaceView (hardware overlay)
 * — the standard-API counterpart of Sony's private OUTPUT_VIDEO_MAIN plane.
 *
 * The service pushes decrypted media here via MirrorActivityBridge; we own the
 * decode/render pipelines and the shared clock.
 */
class MirrorActivity : Activity(), SurfaceHolder.Callback {

    private val clock = MirrorClock()
    private val video = VideoPipeline(clock)
    private val audio = AudioPipeline(clock)

    private lateinit var surfaceView: SurfaceView
    private var surfaceReady = false

    // Pending config captured before the surface is ready.
    private var pendingCodec = -1
    private var pendingW = 0
    private var pendingH = 0
    private var pendingCsd0: ByteArray? = null
    private var pendingCsd1: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this).also { setContentView(it); it.holder.addCallback(this) }
        AirPlayService.start(this)       // ensure the core is running
    }

    override fun onStart() { super.onStart(); MirrorActivityBridge.sink = this }
    override fun onStop() { if (MirrorActivityBridge.sink === this) MirrorActivityBridge.sink = null; super.onStop() }

    override fun onDestroy() { video.stop(); audio.stop(); super.onDestroy() }

    // --- surface lifecycle ---
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
        surfaceReady = true
        pendingCsd0?.let { startVideo(holder, pendingCodec, pendingW, pendingH, it, pendingCsd1) }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; video.stop() }

    // --- callbacks from the core (via the bridge) ---
    fun onVideoConfig(codec: Int, w: Int, h: Int, csd0: ByteArray, csd1: ByteArray?) {
        pendingCodec = codec; pendingW = w; pendingH = h; pendingCsd0 = csd0; pendingCsd1 = csd1
        if (surfaceReady) startVideo(surfaceView.holder, codec, w, h, csd0, csd1)
    }

    fun onVideoFrame(data: ByteArray, ptsUs: Long, isIdr: Boolean) {
        // The native core already delivers Annex-B byte-stream (start codes) with
        // SPS/PPS prepended inline (see UxPlay raop_rtp_mirror). Feed it verbatim.
        video.submitFrame(java.nio.ByteBuffer.wrap(data), ptsUs, isIdr, isConfig = false)
    }

    fun onAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) =
        audio.configure(format, sampleRate, channels, csd0)

    fun onAudioPacket(data: ByteArray, ptsUs: Long) = audio.submit(data, ptsUs)

    fun onTimingSync(streamPtsUs: Long, localPlaybackUs: Long) = clock.updateSync(streamPtsUs, localPlaybackUs)

    fun finishMirror() = runOnUiThread { finish() }

    // --- helpers ---
    private fun startVideo(holder: SurfaceHolder, codec: Int, w: Int, h: Int, csd0: ByteArray, csd1: ByteArray?) {
        val type = if (codec == 1) VideoPipeline.Codec.HEVC else VideoPipeline.Codec.H264
        video.start(holder.surface, type, w, h, ByteBuffer.wrap(csd0), csd1?.let { ByteBuffer.wrap(it) })
    }

    /** AVCC (4-byte length prefix) → Annex-B start codes; see SONY-SPEC.md step 5. */
    private fun toAnnexB(avcc: ByteArray): ByteBuffer {
        val out = ByteBuffer.allocateDirect(avcc.size)
        var i = 0
        while (i + 4 <= avcc.size) {
            val len = ((avcc[i].toInt() and 0xFF) shl 24) or ((avcc[i + 1].toInt() and 0xFF) shl 16) or
                ((avcc[i + 2].toInt() and 0xFF) shl 8) or (avcc[i + 3].toInt() and 0xFF)
            out.put(0); out.put(0); out.put(0); out.put(1)
            i += 4
            if (len <= 0 || i + len > avcc.size) break
            out.put(avcc, i, len); i += len
        }
        out.flip(); return out
    }
}
