package com.airplay.receiver.net

/**
 * JNI bridge to the reused native protocol/crypto core (RTSP server, pairing,
 * FairPlay `/fp-setup` handshake, RTP depacketize + decrypt). See
 * native-core/README.md for what gets vendored here and why it is reused rather
 * than rewritten (the FairPlay key material is fixed and shared across all FOSS
 * receivers — it cannot be derived from scratch).
 *
 * The core runs the socket + crypto work on its own native threads and calls
 * back into [Callbacks] with already-decrypted media access units, which we feed
 * straight into VideoPipeline / AudioPipeline.
 *
 * This class is the ONLY place that talks to the native core. Everything above
 * it is pure Kotlin/Android.
 */
class AirPlayCore(private val cb: Callbacks) {

    interface Callbacks {
        /** H.264/HEVC config (SPS/PPS/VPS), Annex-B framed. */
        fun onVideoConfig(codec: Int, width: Int, height: Int, csd0: ByteArray, csd1: ByteArray?)
        /** One decrypted, Annex-B access unit. */
        /** [captureNs]: sender capture time on the System.nanoTime() clock. */
        fun onVideoFrame(data: ByteArray, captureNs: Long, isIdr: Boolean)
        /** Audio format negotiated at SETUP (0=ALAC 1=AAC_LC 2=AAC_ELD, -1 unknown), + csd. */
        fun onAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?)
        /** One decrypted audio packet. [ptsUs]: sender timestamp in µs. */
        fun onAudioPacket(data: ByteArray, ptsUs: Long)
        /** Sender volume in dB (-144 = mute, else about -30..0). */
        fun onAudioVolume(volumeDb: Float)
        /** Sender paused/seeked: drop queued audio. */
        fun onAudioFlush()
        fun onSessionStart()
        fun onSessionEnd()
    }

    /** Starts the RTSP/HTTP server on [port] (7000) and returns the TXT record. */
    external fun nativeStart(deviceName: String, port: Int): Map<String, String>
    external fun nativeStop()

    // --- forwarding targets called from native (JNI) ---
    // Names/signatures must match the GetMethodID calls in airplaycore.cpp.
    @Suppress("unused")
    private fun nvVideoConfig(codec: Int, width: Int, height: Int, csd0: ByteArray, csd1: ByteArray?) =
        cb.onVideoConfig(codec, width, height, csd0, csd1)
    @Suppress("unused")
    private fun nvVideoFrame(data: ByteArray, captureNs: Long, isIdr: Boolean) =
        cb.onVideoFrame(data, captureNs, isIdr)
    @Suppress("unused")
    private fun nvAudioConfig(format: Int, sampleRate: Int, channels: Int, csd0: ByteArray?) =
        cb.onAudioConfig(format, sampleRate, channels, csd0)
    @Suppress("unused")
    private fun nvAudioPacket(data: ByteArray, ptsUs: Long) = cb.onAudioPacket(data, ptsUs)
    @Suppress("unused")
    private fun nvAudioVolume(volumeDb: Float) = cb.onAudioVolume(volumeDb)
    @Suppress("unused")
    private fun nvAudioFlush() = cb.onAudioFlush()
    @Suppress("unused")
    private fun nvSessionStart() = cb.onSessionStart()
    @Suppress("unused")
    private fun nvSessionEnd() = cb.onSessionEnd()

    companion object {
        init {
            // Loaded once the core is vendored + CMake wired in app/build.gradle.kts.
            runCatching { System.loadLibrary("airplaycore") }
        }
    }
}
