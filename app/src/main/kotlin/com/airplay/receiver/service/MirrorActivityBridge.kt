package com.airplay.receiver.service

import android.content.Context
import android.content.Intent
import com.airplay.receiver.MirrorActivity

/**
 * Thin router between the service-owned native session and the (possibly not yet
 * created) MirrorActivity. The core produces media continuously; the activity
 * registers itself here while in front and consumes it. Buffering/early frames
 * before the activity attaches are dropped — the first IDR after attach starts
 * clean (matching the Sony "first frame after IDR is always shown" rule).
 */
object MirrorActivityBridge {
    @Volatile var sink: MirrorActivity? = null

    fun launch(ctx: Context) {
        ctx.startActivity(Intent(ctx, MirrorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    fun finish() { sink?.finishMirror() }

    fun onVideoConfig(ctx: Context, codec: Int, w: Int, h: Int, csd0: ByteArray, csd1: ByteArray?) {
        sink?.onVideoConfig(codec, w, h, csd0, csd1) ?: launch(ctx)
    }
    fun onVideoFrame(data: ByteArray, ptsUs: Long, isIdr: Boolean) = sink?.onVideoFrame(data, ptsUs, isIdr) ?: Unit
    fun onAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) =
        sink?.onAudioConfig(format, sampleRate, channels, csd0) ?: Unit
    fun onAudioPacket(data: ByteArray, ptsUs: Long) = sink?.onAudioPacket(data, ptsUs) ?: Unit
    fun onTimingSync(streamPtsUs: Long, localPlaybackUs: Long) = sink?.onTimingSync(streamPtsUs, localPlaybackUs) ?: Unit
}
