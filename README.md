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
- [x] Reverse-engineering + spec (`docs/`)
- [x] Android app skeleton: service, boot start, mDNS advert (`NsdManager`)
- [x] **Video pipeline** — clean reimplementation of the Sony render thread:
      hw decoder by capability, H.264 **and HEVC**, async `MediaCodec`,
      `low-latency`, SurfaceView, ns-correct frame pacing + IDR rules.
- [x] **Audio pipeline** — ALAC(PCM)/AAC-LC/AAC-ELD, csd per spec, AudioTrack.
- [x] JNI contract to the native core (`AirPlayCore.kt`, `native-core/README.md`)
- [ ] Vendor + wire the native protocol core (the one reused piece)
- [ ] End-to-end bring-up on the dongle
- [ ] A/V sync tuning against the real timing channel

## Scope (unprivileged, by necessity)
Classic AirPlay: screen mirroring + audio. **Not** AirPlay-2 buffered audio /
multi-room / HDMI-CEC wake — those need PTP on ports 319/320, i.e. root, which the
dongle's locked bootloader denies (see `docs/DONGLE-CAPS.md`).

## Building
Needs Android SDK (API 34) + NDK. Not yet wired to the native core, so the app
layer builds but has no media source until `native-core/` is vendored.
`./gradlew :app:assembleDebug` once a `local.properties` points at your SDK.
```
```
