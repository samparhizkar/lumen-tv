package io.github.samparhizkar.lumen

import android.app.Activity
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import io.github.samparhizkar.lumen.media.VideoPipeline
import io.github.samparhizkar.lumen.service.AirPlayService
import io.github.samparhizkar.lumen.service.MirrorActivityBridge
import java.nio.ByteBuffer

/**
 * Full-screen mirroring surface. Two ways to put decoded video on screen:
 *
 *  - **Direct (default):** a SurfaceView on the TV's hardware video plane — the
 *    standard-API counterpart of Sony's private OUTPUT_VIDEO_MAIN plane. Full
 *    60 fps at no GPU cost. On the Homatics/Amlogic box this plane clipped
 *    highlights (about 240+ merged into white) before minimal post-processing
 *    was requested.
 *  - **Accurate colors (opt-in):** a TextureView, so the GPU composites the
 *    video into the app window like any other pixels, with exact full-range
 *    levels. Measured on the Amlogic S905Y4: ~48 ms of GPU per frame, i.e.
 *    only ~40 fps, so it is not the default.
 *
 * The service pushes decrypted video here via MirrorActivityBridge; this screen
 * owns the video pipeline. Audio is played by the service, so it also works for
 * audio-only AirPlay where no screen opens.
 */
class MirrorActivity : Activity() {

    private lateinit var video: VideoPipeline

    private var outputSurface: Surface? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    // Config captured before the output surface is ready.
    private var pendingCodec = -1
    private var pendingW = 0
    private var pendingH = 0
    private var pendingCsd0: ByteArray? = null
    private var pendingCsd1: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // "Game mode": asks the box to bypass its video picture-processing and,
        // over HDMI (ALLM / game content type), asks the TV to drop its own
        // processing delay. Mirroring a desktop wants neither enhancement nor lag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setPreferMinimalPostProcessing(true)
        }
        if (Prefs(this).accurateColors) {
            // TextureView consumers ignore buffer timestamps, so the pipeline
            // times each release itself.
            video = VideoPipeline(selfTimedRelease = true)
            setContentView(TextureView(this).apply { surfaceTextureListener = textureListener })
        } else {
            video = VideoPipeline(selfTimedRelease = false)
            setContentView(SurfaceView(this).apply { holder.addCallback(holderCallback) })
        }
        AirPlayService.start(this)       // ensure the core is running
    }

    // The bridge replays the stream so far (config + frames since the last
    // keyframe) on attach, so a screen that opens mid-stream still decodes.
    override fun onStart() {
        super.onStart()
        holdLowLatencyWifi()
        MirrorActivityBridge.attach(this)
    }

    override fun onStop() {
        MirrorActivityBridge.detach(this)
        wifiLocks.forEach { runCatching { it.release() } }
        wifiLocks.clear()
        super.onStop()
    }

    override fun onDestroy() { video.stop(); super.onDestroy() }

    /**
     * Turns off Wi-Fi power saving while the mirror is on screen. Power-save
     * batching is a common cause of frames arriving in bursts, which reads as a
     * stutter. Both lock types are taken: LOW_LATENCY (only effective while this
     * activity is in front, and only partly honored on chips without a native
     * low-latency mode) plus HIGH_PERF, which asks the driver to disable power
     * save outright.
     */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: still the direct power-save switch
    private fun holdLowLatencyWifi() {
        if (wifiLocks.isNotEmpty()) return
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val modes = buildList {
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
        }
        for (mode in modes) {
            wifi.createWifiLock(mode, "lumen:mirror:$mode").apply {
                setReferenceCounted(false)
                if (runCatching { acquire() }.isSuccess) wifiLocks += this
            }
        }
    }

    // --- output surface lifecycle (whichever view is in use) ---

    private fun onOutputReady(surface: Surface) {
        outputSurface = surface
        // Surface callbacks can fire more than once; restarting the decoder
        // would drop the keyframe it already consumed and leave the screen black.
        if (!video.isRunning) {
            pendingCsd0?.let { startVideo(surface, pendingCodec, pendingW, pendingH, it, pendingCsd1) }
        }
    }

    private fun onOutputGone() {
        outputSurface = null
        video.stop()
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {}
        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) = onOutputReady(holder.surface)
        override fun surfaceDestroyed(holder: SurfaceHolder) = onOutputGone()
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = onOutputReady(Surface(st))
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            val surface = outputSurface
            onOutputGone()
            surface?.release()
            return true
        }
    }

    // --- callbacks from the core (via the bridge) ---
    fun onVideoConfig(codec: Int, w: Int, h: Int, csd0: ByteArray, csd1: ByteArray?) {
        val codecChanged = pendingCodec != -1 && pendingCodec != codec
        pendingCodec = codec; pendingW = w; pendingH = h; pendingCsd0 = csd0; pendingCsd1 = csd1
        val surface = outputSurface ?: return
        // Same codec at a new size is handled in place (adaptive playback);
        // only a codec switch (H.264 <-> HEVC) needs a new decoder.
        if (codecChanged) video.stop()
        if (!video.isRunning) startVideo(surface, codec, w, h, csd0, csd1)
    }

    fun onVideoFrame(data: ByteArray, captureNs: Long, isIdr: Boolean) {
        // The native core already delivers Annex-B byte-stream (start codes) with
        // SPS/PPS prepended inline (see UxPlay raop_rtp_mirror). Feed it verbatim.
        video.submitFrame(ByteBuffer.wrap(data), captureNs, isIdr, isConfig = false)
    }

    fun finishMirror() = runOnUiThread { finish() }

    // --- helpers ---
    private fun startVideo(surface: Surface, codec: Int, w: Int, h: Int, csd0: ByteArray, csd1: ByteArray?) {
        val type = if (codec == 1) VideoPipeline.Codec.HEVC else VideoPipeline.Codec.H264
        video.start(surface, type, w, h, ByteBuffer.wrap(csd0), csd1?.let { ByteBuffer.wrap(it) })
    }
}
