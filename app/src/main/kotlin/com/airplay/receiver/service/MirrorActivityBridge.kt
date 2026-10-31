package com.airplay.receiver.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.airplay.receiver.MirrorActivity

/**
 * Router between the service-owned native session and MirrorActivity.
 *
 * The core starts streaming as soon as a sender connects, but the activity can
 * appear a moment later — or, if Android blocked the launch, only once the user
 * has let Beam display over other apps. So the bridge keeps what a newly
 * attached activity needs to start decoding mid-stream:
 *  - the latest video/audio config, and
 *  - every video access unit since the most recent keyframe (one GOP), because
 *    the decoder can only start from a keyframe that carries SPS/PPS.
 * On attach both are replayed in order, then live frames follow.
 *
 * All state is guarded by [lock]: media arrives on native threads while
 * attach/detach run on the main thread.
 */
object MirrorActivityBridge {
    private const val TAG = "MirrorBridge"
    private const val GOP_CAP_BYTES = 32L * 1024 * 1024
    private const val RELAUNCH_INTERVAL_MS = 3_000L

    private class VideoConfig(val codec: Int, val w: Int, val h: Int, val csd0: ByteArray, val csd1: ByteArray?)
    private class AudioConfig(val format: Int, val sampleRate: Int, val channels: Int, val csd0: ByteArray?)
    private class Frame(val data: ByteArray, val captureNs: Long, val isIdr: Boolean)

    private val lock = Any()
    private var sink: MirrorActivity? = null
    private var appContext: Context? = null
    private var videoConfig: VideoConfig? = null
    private var audioConfig: AudioConfig? = null
    private val gop = ArrayList<Frame>()
    private var gopBytes = 0L
    private var haveKeyframe = false
    private var attachedThisSession = false
    private var lastLaunchMs = 0L

    /** Called by MirrorActivity when it becomes visible. Replays the stream so far. */
    fun attach(activity: MirrorActivity) = synchronized(lock) {
        sink = activity
        attachedThisSession = true
        videoConfig?.let { activity.onVideoConfig(it.codec, it.w, it.h, it.csd0, it.csd1) }
        audioConfig?.let { activity.onAudioConfig(it.format, it.sampleRate, it.channels, it.csd0) }
        if (haveKeyframe) gop.forEach { activity.onVideoFrame(it.data, it.captureNs, it.isIdr) }
    }

    fun detach(activity: MirrorActivity) = synchronized(lock) {
        if (sink === activity) sink = null
    }

    fun launch(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            appContext = app
            lastLaunchMs = SystemClock.elapsedRealtime()
        }
        // Android 14 blocks this from a background service unless Beam may display
        // over other apps; the home screen asks the user for that permission.
        runCatching {
            app.startActivity(
                Intent(app, MirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { Log.w(TAG, "mirror launch failed", it) }
    }

    /** Session over: close the mirror screen and drop all cached stream state. */
    fun finish() {
        val s = synchronized(lock) {
            videoConfig = null
            audioConfig = null
            gop.clear(); gopBytes = 0; haveKeyframe = false
            attachedThisSession = false
            sink
        }
        s?.finishMirror()
    }

    fun onVideoConfig(ctx: Context, codec: Int, w: Int, h: Int, csd0: ByteArray, csd1: ByteArray?) {
        val delivered = synchronized(lock) {
            appContext = ctx.applicationContext
            videoConfig = VideoConfig(codec, w, h, csd0, csd1)
            sink?.onVideoConfig(codec, w, h, csd0, csd1) != null
        }
        if (!delivered) launch(ctx)
    }

    fun onVideoFrame(data: ByteArray, captureNs: Long, isIdr: Boolean) {
        var relaunchFrom: Context? = null
        synchronized(lock) {
            val hevc = videoConfig?.codec == 1
            if (startsWithKeyframe(data, hevc)) {
                gop.clear(); gopBytes = 0; haveKeyframe = true
            }
            if (haveKeyframe) {
                gop.add(Frame(data, captureNs, isIdr))
                gopBytes += data.size
                // A runaway GOP (no keyframe for a long time) is dropped; the
                // next keyframe starts a fresh one.
                if (gopBytes > GOP_CAP_BYTES) {
                    gop.clear(); gopBytes = 0; haveKeyframe = false
                }
            }
            val s = sink
            if (s != null) {
                s.onVideoFrame(data, captureNs, isIdr)
            } else if (!attachedThisSession) {
                // The screen never came up for this session (launch raced or was
                // blocked): keep retrying, but never fight a user who left it.
                val now = SystemClock.elapsedRealtime()
                if (now - lastLaunchMs > RELAUNCH_INTERVAL_MS) {
                    lastLaunchMs = now
                    relaunchFrom = appContext
                }
            }
        }
        relaunchFrom?.let { launch(it) }
    }

    fun onAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) {
        synchronized(lock) {
            audioConfig = AudioConfig(format, sampleRate, channels, csd0)
            sink?.onAudioConfig(format, sampleRate, channels, csd0)
        }
    }

    fun onAudioPacket(data: ByteArray, ptsUs: Long) {
        synchronized(lock) { sink }?.onAudioPacket(data, ptsUs)
    }

    fun onTimingSync(streamPtsUs: Long, localPlaybackUs: Long) {
        synchronized(lock) { sink }?.onTimingSync(streamPtsUs, localPlaybackUs)
    }

    /**
     * True if the Annex-B access unit opens a GOP: a parameter set or IDR slice
     * among its leading NAL units. The core prepends SPS/PPS(/VPS) to keyframes.
     */
    private fun startsWithKeyframe(au: ByteArray, hevc: Boolean): Boolean {
        val limit = minOf(au.size - 4, 512)
        var i = 0
        while (i < limit) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 0 && au[i + 3].toInt() == 1) {
                val header = au[i + 4].toInt() and 0xFF
                if (hevc) {
                    when ((header shr 1) and 0x3F) { 32, 33, 34, 19, 20, 21 -> return true } // VPS/SPS/PPS/IDR/CRA
                } else {
                    when (header and 0x1F) { 7, 8, 5 -> return true } // SPS/PPS/IDR
                }
                i += 4
            } else {
                i++
            }
        }
        return false
    }
}
