# Homatics Dongle R 4K — capabilities (measured)

Device: `SEI700DHM` / model `Dongle R 4K` (SEI Robotics), Amlogic **S905Y4**,
Android 14 (API 34), kernel 5.15 (GKI), **32-bit userspace** (`armeabi-v7a` only),
adb over network `192.168.8.153:5555` / usb serial `GZ25040772204345`.

## Root status — NOT rooted (decision: ship as a normal app)
- Bootloader **locked**, verified-boot `green`, `secure=yes`.
- Android "OEM unlocking" toggle is ON (`sys.oem_unlock_allowed=1`) and bootloader
  reports `get_unlock_ability: 1`, BUT the vendor bootloader rejects unlock:
  - `fastboot flashing unlock` → `please unlock oem lock first`
  - `fastboot oem unlock` → `cmd unlock not in secure boot white list`
- `fastboot boot` is accepted-but-gated (`locked device`) → would work once unlocked.
- `fastboot fetch` unsupported; no public firmware for this model; vendor OTA
  (`aosp-ota.skywayplatform.com:8443`) returns up-to-date for spoofed versions.
- Conclusion: no clean unlock path. We do NOT root. Consequence: no AirPlay-2
  buffered audio / PTP (ports 319/320) / HDMI-CEC wake. Classic AirPlay only.

## Video decoders (from /vendor/etc/media_codecs_amlogic_*.xml)
Both H.264 and HEVC hardware decoders advertise the features we want:
- `c2.amlogic.avc.decoder`  — size ≤ 4096×4096; features **adaptive-playback,
  tunneled-playback, low-latency**; perf points 1920×1088@60, 3840×2160@30.
- `c2.amlogic.hevc.decoder` — size ≤ 4096×4096; same features; perf points
  1920×1080@246(!), **3840×2160@60**.
- Also av1/vp9 hw decoders, avc/hevc hw **encoders** present.

### What this lets us beat on vs. the Sony
- Sony mirroring is H.264 capped at 1080p. We can accept **HEVC** from senders
  (ScreenMultiCodec feature bit) and do **4K60 HEVC / 1080p60 H.264**.
- `tunneled-playback` + `low-latency` are the standard-API counterparts of Sony's
  private `X-tv-output-path=OUTPUT_VIDEO_MAIN`.

## Already-installed reference
- `io.github.jqssun.airplay` (a UxPlay-based receiver) is present — NOT our base,
  kept only as a comparison/sanity target.

## Build/runtime notes
- Install target is `armeabi-v7a`. Native core + any Rust must build for
  `armv7-linux-androideabi` (API 34). SurfaceView overlay for video.
- App can auto-start via a one-time adb grant; no system-app privileges.
