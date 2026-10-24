# native-core — reused AirPlay protocol/crypto core

This directory holds the C protocol core that the Kotlin app wraps over JNI
(`com.airplay.receiver.net.AirPlayCore`). It is **reused, not rewritten** — see
the architecture decision in the top-level README.

## Why reused
The FairPlay `/fp-setup` handshake requires fixed Apple-derived key material that
was extracted from Apple hardware years ago. It cannot be derived or "written
better"; every open-source receiver embeds the same constants. Reimplementing the
RTSP/pairing/RTP state machine from scratch would also be months of fiddly work
with zero quality gain on the wire. Our engineering effort goes into the Android
media pipeline (the Kotlin side), which is where we beat the Sony.

## What to vendor here
A mirroring-capable receiver core from the RPiPlay/UxPlay lineage (`lib/`), which
already implements, for both audio and mirroring:
- RTSP/HTTP server (port 7000), `/info`, `/command`, `/feedback`
- pairing (`/pair-setup`, `/pair-verify`) and FairPlay (`/fp-setup`)
- RTP depacketization + AES decrypt for audio and video
- ALAC decode (video H.264/HEVC access units are passed up undecoded)

NOTE on licensing: that lineage is GPLv3. Keep this core as a separate,
clearly-attributed module; our Kotlin app calls it over a JNI boundary. Record the
exact upstream commit and license here when vendored. Confirm the license posture
you want before shipping binaries.

## Build wiring (when vendored)
1. Add `CMakeLists.txt` here producing `libairplaycore.so` for `armeabi-v7a`.
2. Uncomment the `externalNativeBuild` block in `app/build.gradle.kts`.
3. Implement the JNI functions declared in `AirPlayCore.kt`, calling the
   `Callbacks` with decrypted access units + timing sync points.

## Interface contract (what the core must deliver to Kotlin)
Matches `AirPlayCore.Callbacks`:
- `onVideoConfig(codec, w, h, csd0, csd1)` — codec 0=H264 1=HEVC, csd Annex-B.
- `onVideoFrame(data, ptsUs, isIdr)` — one decrypted Annex-B access unit.
- `onAudioConfig(format, sampleRate, channels, csd0)` — 0=ALAC(PCM) 1=AAC_LC 2=AAC_ELD.
- `onAudioPacket(data, ptsUs)` — decrypted audio (PCM for ALAC).
- `onTimingSync(streamPtsUs, localPlaybackUs)` — NTP-clock sync point.
- `onSessionStart()` / `onSessionEnd()`.
