# Third-party notices

Beam bundles and links the following third-party components. Their license
files are kept alongside their sources under `native-core/`.

## UxPlay airplay core — GPLv3
- Source: https://github.com/FDH2/UxPlay (lineage: RPiPlay / shairport)
- Files: `native-core/airplay/*.c` (except `dnssd_backend.c`, which is ours),
  `native-core/airplay/llhttp/`, `native-core/airplay/playfair/`
- License: GNU General Public License v3 — `native-core/airplay/LICENSE`
- **Because Beam links this component, Beam as a whole is distributed under the
  GPLv3** (see top-level `LICENSE`).

### llhttp (within the core) — MIT
- Source: https://github.com/nodejs/llhttp
- Files: `native-core/airplay/llhttp/` · License: `.../llhttp/LICENSE-MIT`

### playfair (within the core) — see its notice
- Files: `native-core/airplay/playfair/` · License: `.../playfair/LICENSE.md`

## libplist — LGPL-2.1+
- Source: https://github.com/libimobiledevice/libplist
- Files: `native-core/third_party/libplist/` (compiled from source)
- License: GNU Lesser General Public License v2.1 — `.../libplist/COPYING`

## OpenSSL (libcrypto) — Apache-2.0
- Source: https://github.com/openssl/openssl (3.3.2)
- Files: prebuilt `native-core/third_party/openssl/lib/libcrypto.a` + headers,
  cross-compiled for `armeabi-v7a` (Android API 28)
- License: Apache License 2.0 — `.../openssl/LICENSE.txt`

## Our own code
Everything under `app/` and the JNI bridge / glue in
`native-core/jni/airplaycore.cpp` and `native-core/airplay/dnssd_backend.c` is
original to this project, and is also covered by the top-level GPLv3 license.

---

Beam is an independent project. It is not affiliated with, endorsed by, or
sponsored by Apple Inc. "AirPlay" is a trademark of Apple Inc., used here only
to describe interoperability.
