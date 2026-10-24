package com.airplay.receiver.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Audio decode + output. Formats and csd follow the Sony spec
 * (AACELDNdkDecoder_Configure): ALAC / AAC-LC / AAC-ELD.
 *
 * Output mirrors the Sony's choice — a plain MUSIC-usage AudioTrack — but we lean
 * on `getTimestamp()` for A/V sync instead of its position-period polling, and
 * can later move to AAudio for lower latency. ALAC has no platform decoder; it is
 * decoded in the native core and arrives here as PCM.
 */
class AudioPipeline(private val clock: MirrorClock) {

    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var pcmMode = false

    fun configure(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) {
        stop()
        openTrack(sampleRate, channels)
        when (format) {
            FORMAT_ALAC -> pcmMode = true  // core delivers PCM
            FORMAT_AAC_LC, FORMAT_AAC_ELD -> openAacDecoder(sampleRate, channels, format, csd0)
        }
        Log.i(TAG, "audio configured fmt=$format $sampleRate/$channels pcm=$pcmMode")
    }

    fun submit(data: ByteArray, ptsUs: Long) {
        if (pcmMode) { track?.write(data, 0, data.size); return }
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(0)
        if (inIdx >= 0) {
            mc.getInputBuffer(inIdx)?.apply { clear(); put(data) }
            mc.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIdx = mc.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            mc.getOutputBuffer(outIdx)?.let { buf ->
                val pcm = ByteArray(info.size); buf.get(pcm); track?.write(pcm, 0, pcm.size)
            }
            mc.releaseOutputBuffer(outIdx, false)
            outIdx = mc.dequeueOutputBuffer(info, 0)
        }
    }

    fun stop() {
        runCatching { codec?.stop(); codec?.release() }
        runCatching { track?.stop(); track?.release() }
        codec = null; track = null; pcmMode = false
    }

    private fun openTrack(sampleRate: Int, channels: Int) {
        val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
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
            .setBufferSizeInBytes(maxOf(min, sampleRate / 10 * channels * 2))
            .build()
            .also { it.play() }
    }

    private fun openAacDecoder(sampleRate: Int, channels: Int, format: Int, csd0: ByteArray?) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            // profile: AAC-ELD == 39, AAC-LC == 2 (OMX aac-profile in Sony RE)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                if (format == FORMAT_AAC_ELD) MediaCodecProfiles.AAC_ELD else MediaCodecProfiles.AAC_LC)
            val csd = csd0 ?: defaultEldCsd(sampleRate, channels, format)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(fmt, null, null, 0); start()
        }
    }

    /** csd-0 construction from SONY-SPEC.md (AAC-ELD 2-byte, else fixed 4-byte). */
    private fun defaultEldCsd(sampleRate: Int, channels: Int, format: Int): ByteArray {
        if (format == FORMAT_AAC_ELD) {
            val fi = freqIndex(sampleRate)
            return byteArrayOf(((fi shr 1) or 0x10).toByte(), ((fi shl 7) or (channels shl 3)).toByte())
        }
        return byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
    }

    private fun freqIndex(sr: Int) = when (sr) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
        24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> 4
    }

    private object MediaCodecProfiles { const val AAC_LC = 2; const val AAC_ELD = 39 }

    companion object {
        private const val TAG = "AudioPipeline"
        const val FORMAT_ALAC = 0
        const val FORMAT_AAC_LC = 1
        const val FORMAT_AAC_ELD = 2
    }
}
