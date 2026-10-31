package com.airplay.receiver.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * AirPlay audio decode + output, owned by the service so it plays whether or not
 * the mirror screen is up (audio-only AirPlay has no screen at all).
 *
 * Formats and csd follow the Sony spec (AACELDNdkDecoder_Configure): AAC-ELD is
 * what macOS screen mirroring sends (44.1 kHz stereo, 480-sample frames),
 * decoded by Google's software AAC decoder; ALAC has no platform decoder.
 *
 * Packets arrive on the core's audio thread; configure/stop/volume on others.
 * Everything is guarded by [lock].
 */
class AudioPipeline {

    private val lock = Any()
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var format = FORMAT_NONE
    private var sampleRate = 44_100
    private var channels = 2
    private var volume = 1f
    private var dropped = 0
    private val info = MediaCodec.BufferInfo()

    fun configure(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?): Unit = synchronized(lock) {
        releaseLocked()
        this.format = format
        this.sampleRate = sampleRate
        this.channels = channels
        when (format) {
            FORMAT_AAC_LC, FORMAT_AAC_ELD -> {
                openTrackLocked()
                openAacDecoderLocked(csd0 ?: defaultCsd(format))
            }
            FORMAT_ALAC -> Log.w(TAG, "ALAC audio not supported yet; playing silence")
            else -> Log.w(TAG, "unknown audio format $format")
        }
        Log.i(TAG, "audio configured fmt=$format ${sampleRate}Hz/${channels}ch decoder=${codec?.name}")
    }

    /** One encoded access unit. [ptsUs]: sender timestamp (ordering only for now). */
    fun submit(data: ByteArray, ptsUs: Long): Unit = synchronized(lock) {
        val mc = codec ?: return
        try {
            // A short wait instead of 0: with 0 a momentarily busy decoder made
            // us drop the packet, which is an audible click.
            val inIdx = mc.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inIdx < 0) {
                if (++dropped % 50 == 1) Log.w(TAG, "decoder busy, dropped $dropped packets")
                return
            }
            mc.getInputBuffer(inIdx)?.apply { clear(); put(data) }
            mc.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
            drainLocked(mc)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio decoder error", e)
        }
    }

    /**
     * Sender volume in dB: 0 is full, -30 is the bottom of AirPlay's range, -144
     * is the explicit mute code. A Mac sends the bottom of the range (not -144)
     * for mute / volume zero, so everything at or below -30 dB is silence —
     * a faint 3% there sounded like "mute doesn't work".
     */
    fun setVolumeDb(db: Float): Unit = synchronized(lock) {
        volume = if (db <= MIN_DB) 0f else 10f.pow(db.coerceAtMost(0f) / 20f)
        Log.i(TAG, "sender volume %.2f dB -> gain %.3f".format(db, volume))
        track?.setVolume(volume)
    }

    /** Sender paused or seeked: drop everything queued so playback resumes cleanly. */
    fun flush(): Unit = synchronized(lock) {
        runCatching { codec?.flush() }
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    fun stop(): Unit = synchronized(lock) { releaseLocked() }

    // --- internals (caller holds lock) ---

    private fun drainLocked(mc: MediaCodec) {
        while (true) {
            val out = mc.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    mc.getOutputBuffer(out)?.let { pcm ->
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        track?.write(pcm, info.size, AudioTrack.WRITE_BLOCKING)
                    }
                    mc.releaseOutputBuffer(out, false)
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = mc.outputFormat
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (sr != sampleRate || ch != channels) {
                        sampleRate = sr; channels = ch
                        releaseTrackLocked()
                        openTrackLocked()
                    }
                }
                else -> return  // INFO_TRY_AGAIN_LATER
            }
        }
    }

    private fun openTrackLocked() {
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        // ~150 ms of room absorbs Wi-Fi jitter; blocking writes pace the
        // core's audio thread to real time once it fills.
        val bytes = maxOf(min, sampleRate * channels * 2 * 150 / 1000)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(chMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .build()
            .also { it.setVolume(volume); it.play() }
    }

    private fun openAacDecoderLocked(csd: ByteArray) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, if (format == FORMAT_AAC_ELD) AAC_ELD else AAC_LC)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        codec = MediaCodec.createByCodecName(pickAacDecoder()).apply {
            configure(fmt, null, null, 0)
            start()
        }
    }

    /**
     * Google's software AAC decoder (Fraunhofer FDK) handles AAC-ELD; the
     * Amlogic hardware AAC decoder is listed first but is not guaranteed to.
     */
    private fun pickAacDecoder(): String {
        val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } }
            .map { it.name }
        return PREFERRED_AAC.firstOrNull { it in names } ?: names.first()
    }

    private fun releaseTrackLocked() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

    private fun releaseLocked() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        releaseTrackLocked()
        format = FORMAT_NONE
    }

    /**
     * AudioSpecificConfig. AAC-ELD as AirPlay sends it is the fixed `F8 E8 50 00`
     * (ELD, 44.1 kHz, stereo, 480-sample frames); AAC-LC gets the 2-byte form
     * built from the sample-rate index and channel count.
     */
    private fun defaultCsd(format: Int): ByteArray {
        if (format == FORMAT_AAC_ELD) return byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
        val fi = freqIndex(sampleRate)
        return byteArrayOf(((AAC_LC shl 3) or (fi shr 1)).toByte(), (((fi and 1) shl 7) or (channels shl 3)).toByte())
    }

    private fun freqIndex(sr: Int) = when (sr) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
        24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> 4
    }

    companion object {
        private const val TAG = "AudioPipeline"
        const val FORMAT_NONE = -1
        const val FORMAT_ALAC = 0
        const val FORMAT_AAC_LC = 1
        const val FORMAT_AAC_ELD = 2
        private const val AAC_LC = 2
        private const val AAC_ELD = 39
        private const val MIN_DB = -30f
        private const val INPUT_TIMEOUT_US = 10_000L
        private val PREFERRED_AAC = listOf("c2.android.aac.decoder", "OMX.google.aac.decoder")
    }
}
