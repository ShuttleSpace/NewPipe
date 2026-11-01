# Building libbox (built-in sing-box proxy)

The "Sing-box" proxy mode runs [sing-box](https://github.com/SagerNet/sing-box) directly
inside the app, using its `libbox` Android library (AAR). This document explains how the
AAR is built and the pitfalls we hit, so the binary can be reproduced.

## Why a prebuilt AAR?

`libbox` is a Go project built with `gomobile`. Building it requires Go, the NDK and a
specific JDK, and the output is large (four ABIs, ~270 MB uncompressed / ~92 MB zipped).
Therefore:

- the AAR is **not** committed to the repository;
- it is published as a GitHub release asset named `libbox` on
  `ShuttleSpace/NewPipe`;
- `app/build.gradle.kts` defines a `downloadLibbox` task that fetches
  `https://github.com/ShuttleSpace/NewPipe/releases/download/libbox/libbox.aar`
  (override with `-PlibboxUrl=...`) whenever `app/libs/libbox.aar` is missing;
- CI downloads it before building the Android APKs.

## Build it yourself

```bash
SING_BOX_VERSION=v1.12.7 ./scripts/build_libbox.sh
```

The script clones sing-box at the given tag, installs the SagerNet `gomobile` fork, runs
`gomobile init` (needs the NDK) and then `go run ./cmd/internal/build_libbox -target android`,
which produces `libbox.aar` (and `libbox-legacy.aar`). The main AAR is copied into
`app/libs`.

### Requirements

| Tool        | Version                  | Note                                                         |
|-------------|--------------------------|--------------------------------------------------------------|
| Go          | matches `sing-box` go.mod |                                                                 |
| JDK         | **OpenJDK 17**           | see pitfall below                                            |
| Android SDK | with NDK                 | `ANDROID_HOME` / `ANDROID_NDK_HOME` must point at the NDK    |

## Pitfalls

### JDK must be 17, not 21

The official `cmd/internal/build_libbox` builder asserts
`strings.Contains(javaVersion, "openjdk 17")`. NewPipe's Gradle build uses JDK 21, so
**switch to JDK 17 only for the libbox build**, then switch back to 21 for the app build.

### Android API level

The builder emits two AARs: `libbox.aar` (Android API 24) and `libbox-legacy.aar`
(API 21). Both are below this app's `minSdk` (23), so either works. We ship `libbox.aar`.

### Do not add the `with_purego` build tag

The official builder already selects the right tags (`with_quic`, `with_wireguard`,
`with_utls`, `with_naive_outbound`, `with_clash_api`, `with_tailscale`, …). Adding
`with_purego` (sometimes suggested to avoid a `cronet relocation 315` crash) is **not**
used upstream and can break the FFI surface; do not add it.

### The AAR is huge

Four ABIs are bundled into a single AAR, so the universal APK is ~300 MB. Mitigate with
per-ABI APK splits:

```bash
./gradlew :app:assembleContinuous -PabiSplits=true
```

This produces `app-armeabi-v7a.apk`, `app-arm64-v8a.apk`, `app-x86.apk`,
`app-x86_64.apk` and `app-universal.apk`.

### Package names are fixed

The AAR exposes classes under `io.nekohasekai.libbox` (the `-javapkg=io.nekohasekai`
used by `gomobile bind`). `SingBoxManager` / `SingBoxPlatformInterface` depend on that
exact package — do not change `-javapkg`.

## Using libbox from the app

`SingBoxManager` wraps the lifecycle (`Libbox.setup()` once, then `CommandServer`). The
minimal `PlatformInterface` in `SingBoxPlatformInterface` only implements what a
proxy-only config needs; the TUN/root/bridge/shell methods return safe no-ops.

Config gotchas that cost time:

- an inbound uses **`listen_port`**, not `port`;
- the default route must be set with **`route.final`** (not `outbound` alone);
- **`singbox_local_port` must be stored as a `String`** (the preference is an
  `EditTextPreference`). Storing it as an `int` throws a `ClassCastException` at runtime;
- `Libbox.setup()` must run before any `CommandServer` call, otherwise sing-box fails to
  start with a `PlatformInterface not set` error.
