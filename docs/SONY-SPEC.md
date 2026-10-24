# Sony BRAVIA AirPlay — behavioral spec (from reverse engineering)

Source: `libairplay.so` (AirPlay Receiver, `srcvers=377.25.05`) pulled from a Sony
BRAVIA XBR-65X800H (Android 10, MediaTek), decompiled with Ghidra 12.1.4 and
cross-checked against JADX decompilation of `com.sony.dtv.airplayapp` and live
device observation. This is a **spec to re-implement against**, not Sony/Apple code.

The Sony stack is Apple's licensed AirPlay SDK. We cannot and do not copy it. We
copy the *observable behavior* — codec choices, format keys, frame-pacing rules,
clock handling — which is what makes its mirroring smooth. Our job is to match or
beat each row below with our own code.

## Discovery / control
- Advertises `_airplay._tcp` (+ `_dacp._tcp` for remote control) on TCP **7000**.
- TXT seen live: `features=0x7F8AD0,0x30BCB46`, `srcvers=377.25.05`, `protovers=1.1`,
  `flags=0x644`, `model=XBR-65X800H`, `pk=...`. Notably it does **not** set the
  AirPlay-2 bits (buffered audio / PTP / HK) that an Apple TV sets.
- RTSP verbs handled: `ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, FLUSHBUFFERED,
  TEARDOWN, OPTIONS, POST, GET, PUT`; endpoints `/info`, `/command`, `/feedback`,
  `/fp-setup` (FairPlay), `/pair-setup`, `/pair-verify`.

## Mirroring video pipeline  (decompiled `_ScreenRendererThread`, `ScreenStreamSetAVCC`)
The single most important function. Our pipeline must reproduce these rules.

1. **Startup gate:** waits up to **2000 ms** for an active media session before
   creating the decoder. (We will instead pre-warm — see pipeline notes.)
2. **Codec:** `AMediaCodec_createCodecByName("OMX.MTK.VIDEO.DECODER.AVC")` —
   H.264 only, vendor hardware decoder by explicit name.
3. **Format:**
   - mime `video/avc`
   - `max-width=0x780 (1920)`, `max-height=0x438 (1080)`  ← hard 1080p cap
   - `feature-adaptive-playback=1`
   - `csd-0` / `csd-1` set from the AVCC SPS/PPS (see below)
4. **Sony-specific output routing:** a second `AMediaFormat` with
   `"X-tv-output-path" = "OUTPUT_VIDEO_MAIN"` is pushed via
   `AMediaCodec_setParameters` **before** configure. This sends decoded frames to
   the TV's main video plane (hardware overlay) rather than the app graphics layer.
   Our standard-API equivalent: render onto a **SurfaceView** (overlay), and use
   the decoder's **tunneled** or **low-latency** feature (both supported on the
   dongle — see DONGLE-CAPS).
5. **AVCC → Annco:** each input frame's 4-byte big-endian length prefix is
   overwritten with the `00 00 00 01` start code before `queueInputBuffer`.
   Resolution change → new SPS/PPS re-queued with flag `CODEC_CONFIG (2)`.
6. **Decoder loop:** fully non-blocking — `dequeueInputBuffer(timeout=0)` and
   `dequeueOutputBuffer(timeout=0)`; a `pthread_cond_timedwait` of **5 ms** paces
   the loop. One log line per frame (we drop the logging).
7. **Frame pacing** (output stage, against the synchronized clock, `now` = UpTicks):
   - `pts - now` **> ~1 s in the future** → `releaseOutputBufferAtTime(pts)`
     (scheduled). NOTE: the decompiled code passes a microsecond-domain pts to a
     ns-domain API; if that is a real bug these frames render ~immediately. We will
     pass **nanoseconds** correctly.
   - within ~1 s late → `releaseOutputBuffer(render=true)` (show now).
   - **> 1 s late** → `releaseOutputBuffer(render=false)` (**drop**), EXCEPT the
     first frame after an IDR, which is always shown.
8. **IDR tracking:** remembers the PTS of the latest IDR so it can force-show the
   first decoded frame after a keyframe even when dropping.

## Audio pipeline  (decompiled `AirPlayAudioTrack::Set`, `AACELDNdkDecoder_Configure`)
- Output: Android internal `android::AudioTrack` opened with usage/flags that
  decode to **no low-latency / fast path** (flags arg = 0), content type MUSIC,
  blocking `write`. Sync via `getPosition`/`setPositionUpdatePeriod` + `latency()`.
  Our standard-API equivalent: `AudioTrack` (or AAudio) + `getTimestamp()`.
- AAC-ELD / AAC-LC decoded with `OMX.google.aac.decoder` (software).
  - mime `audio/mp4a-latm`, keys `sample-rate`, `aac-profile`, `channel-count`.
  - ELD (profile==2) csd-0 built from (freqIndex, channels): 2 bytes
    `[ (freqIdx>>1)|0x10 , (freqIdx<<7)|(ch<<3) ]`.
  - otherwise csd-0 = fixed 4 bytes `F8 E8 50 00`.
- Codecs offered: ALAC, AAC-LC, AAC-ELD.

## Clock / timing  (decompiled `PtpService_*`, `AirTunesClock_*`)
- The app does **not** run PTP itself. `PtpService_Start/AddPeer/Stop` are thin
  HIDL calls into Sony's root system service
  `com.sony.dtv.system.precisiontime@1.0::IPtpManager` — i.e. the `airplayptp`
  root daemon. This is why AirPlay-2 timing needs privilege (ports 319/320).
- `AirTunesClock_GetSynchronizedTimeNearUpTicks` just forwards to the active clock
  impl; on classic AirPlay the clock is NTP-based.
- **Implication for us (no root):** classic AirPlay audio+mirroring uses the NTP
  clock path → fully reproducible unprivileged. AirPlay-2 buffered audio / PTP is
  out of reach without root on this dongle. Scope accordingly.

## FairPlay / pairing (not re-implementable from scratch)
- `/fp-setup` + MFi cert from TEE (`MtkMapleService_MTAL_MFi_*`). The FairPlay
  SAP handshake uses fixed Apple-derived key material that every FOSS receiver
  shares; we reuse that proven core rather than inventing it.
