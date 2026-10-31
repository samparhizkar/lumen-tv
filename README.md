# Lumen

**Your screen, on any TV.** An AirPlay receiver for Android TV that turns a
cheap streaming dongle into a wireless display for iPhone, iPad, and Mac.

First target: the **Homatics Dongle R 4K** (Amlogic S905Y4, Android 14). Built to
match or beat the AirPlay experience of a Sony BRAVIA — which runs Apple's
licensed AirPlay SDK — using only standard, unprivileged Android APIs, so it can
run on an ordinary (unrooted) device.

> Lumen is an independent project, not affiliated with or endorsed by Apple.
> "AirPlay" is a trademark of Apple Inc., used only to describe interoperability.
> Lumen is licensed **GPLv3** (it links the GPLv3 UxPlay core); see `LICENSE` and
> `THIRD-PARTY-NOTICES.md`.

## Install on your TV
Lumen isn't on the Play Store yet; install the APK from
[the latest release](https://github.com/samparhizkar/lumen-tv/releases/latest).

**With the remote only** (Telly, Homatics, most Android TV / Google TV boxes):
1. Install **Downloader** (by AFTVnews) from the Play Store on the TV.
2. Allow Downloader to install apps: in the TV's Settings, find *Unknown
   sources* or *Install unknown apps* (usually under Apps → Security &
   restrictions, or Device Preferences → Security & restrictions) and turn it
   on for Downloader.
3. Open Downloader and enter
   `github.com/samparhizkar/lumen-tv/releases/latest/download/Lumen.apk`,
   then install when asked.
4. Open Lumen, pick the room the TV is in, and allow it to appear over other
   apps (so mirroring can start while you're watching something else).

**With a computer:** `adb install Lumen.apk`.

Then on an iPhone, iPad or Mac: Control Center → Screen Mirroring → your TV.
For music from a Mac's Music app, pick "*your TV* Speaker".

**Known limits:** apps with copy protection (Netflix, Disney+, …) usually
mirror as a black screen; sending a video from an app's AirPlay button (rather
than mirroring) isn't supported yet; tested on the Homatics Dongle R 4K (the
dongle that ships with Telly), other boxes should work but are untested.

This project came out of reverse-engineering the Sony's `libairplay.so` and the
dongle's own capabilities. The findings that drive the design are in `docs/`:
- [`docs/SONY-SPEC.md`](docs/SONY-SPEC.md) — the Sony's decompiled behavior
  (codec choices, format keys, frame-pacing rules, clock handling) as a spec.
- [`docs/DONGLE-CAPS.md`](docs/DONGLE-CAPS.md) — measured dongle capabilities and
  why we ship as a normal app (the bootloader can't be unlocked).

## Architecture decision
**Reuse the proven protocol/crypto core; write a new, better Android pipeline.**

The hard protocol parts — RTSP, pairing, the FairPlay `/fp-setup` handshake, RTP
decrypt — depend on fixed Apple key material shared by every FOSS receiver and
cannot be rewritten "better." So that layer is reused as a native library
(`native-core/`). All of our effort goes into the **Android media pipeline**,
which is where the Sony's quality actually lives and where the dongle can exceed
it (HEVC 4K60 vs. the Sony's H.264 1080p cap).

```
 sender ──mDNS/RTSP:7000──▶ native-core (C, reused) ──JNI──▶ AirPlayCore.kt
                                                                  │ decrypted AUs + timing
                                                                  ▼
                                          AirPlayService (foreground, mDNS advert)
                                                                  │
                                                                  ▼
                                   MirrorActivity ── VideoPipeline / AudioPipeline
                                        (SurfaceView)   (hw decode, clock pacing)
```

## Status
Working on the Homatics Dongle R 4K (tested with iPhone and Mac):
- [x] **Screen mirroring** from iPhone, iPad, and Mac: hardware H.264/HEVC
      decode, 1080p60, low latency, aspect-correct (a portrait iPhone is
      pillarboxed; it goes full-screen when an app rotates).
- [x] **Audio** with mirroring (AAC-ELD), sender volume and mute.
- [x] **Audio-only AirPlay**: iPhone; Mac Music via the "<name> Speaker" entry
      (classic AirTunes, RSA-wrapped key, ALAC). A Mac that picks the main entry
      for Music wraps the key with FairPlay v2, which can't be unwrapped.
- [x] First-run setup: room name (e.g. "Living Room TV"), display-over-apps.
- [ ] A/V sync and frame pacing against a calibrated clock (the core's NTP
      exchange never completes with macOS; pacing uses sender timestamps).
- [ ] Real Wi-Fi MAC as the device id; persistent pairing key across renames.
- [x] Release signing.
- [ ] Public release / Play Store.

## Scope (unprivileged, by necessity)
Classic AirPlay: screen mirroring + audio. **Not** AirPlay-2 buffered audio /
multi-room / HDMI-CEC wake — those need PTP on ports 319/320, i.e. root, which the
dongle's locked bootloader denies (see `docs/DONGLE-CAPS.md`).

## Building
Needs the Android SDK (API 34), NDK 27 and CMake 3.22; the native core
(UxPlay, libplist, prebuilt OpenSSL libcrypto) is vendored under `native-core/`.
Build with JDK 17–21 (Android Studio's bundled JBR works):
`./gradlew :app:assembleRelease`. Release builds are much smoother than debug
builds on the dongle; after installing, `adb shell cmd package compile -m
speed-profile -f io.github.samparhizkar.lumen` warms it up further.

Release builds are signed with the key named in `keystore.properties` at the
repo root (git-ignored: `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`); without that file they fall back to the debug key.
