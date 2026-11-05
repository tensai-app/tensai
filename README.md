# tensai

[![CI](https://github.com/tensai-app/tensai/actions/workflows/ci.yml/badge.svg)](https://github.com/tensai-app/tensai/actions/workflows/ci.yml)

天才: your phone, your AI. Open-source Android app that turns a phone into a local AI agent: runs any GGUF model fully on-device ([llama.kt](https://github.com/tensai-app/llama.kt) / llama.cpp), chat UI, tools and phone actions, and later a LAN API server.

Site, docs and changelog: [tensai.page](https://tensai.page).

**Status: v0.3.x, pre-1.0.** It works, but expect changes between releases.

## Install

Download the signed APK from [GitHub Releases](https://github.com/tensai-app/tensai/releases)
and install it. arm64-v8a only, Android 10 (API 29) or later. Models are not
bundled: pick a GGUF from inside the app.

## Build from source

Requirements: JDK 21, Android SDK (platform 35), NDK 27.2, CMake, a host C++
compiler (`g++`), and `glslc` (shaderc, packaged with the Vulkan SDK or as
`shaderc` on most distros).

```bash
git clone --recurse-submodules https://github.com/tensai-app/tensai.git
cd tensai
```

Already cloned without `--recurse-submodules`: `git submodule update --init --recursive`.

The `cpp/` sources are vendored, but two artifacts the native build links
against are generated rather than committed, so a fresh clone does not build:
`assembleDebug` stops on `ggml-vulkan-shaders.hpp file not found`. Generate
both, once.

**1. `libOpenCL.so`** for the llama.kt GPU backend (a few seconds):

```bash
./third_party/llama.kt/scripts/build-opencl.sh
```

**2. Vulkan SPIR-V shaders**: 132 `.comp.cpp` files (134 MB) plus
`ggml-vulkan-shaders.hpp`. `scripts/bootstrap.sh` produces them as part of a
full re-vendoring of `cpp/`; if you only want to build, run the shader script
on its own, with the path to your `glslc`:

```bash
./third_party/llama.kt/scripts/gen-vulkan-shaders.sh "$(which glslc)"
```

Measured: 70 s on 22 cores, 106 s on 4, peaking around 1.1 GB resident across 16
concurrent `glslc`, and 250 MB of intermediates under `/tmp` (removed on exit). The output is
byte-for-byte deterministic for a given `llama.cpp` commit and `glslc` build, so
it caches well. A different `glslc` version yields valid but different SPIR-V.

Then:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI does the same thing, in `.github/workflows/native.yml`: it regenerates both
and caches the shaders. It calls the same script, with the NDK `glslc`. See [docs/ci.md](docs/ci.md).

Target hardware: arm64-v8a only, minSdk 29.

### APK size

Budget: **under 50 MB** for the release APK, models excluded. Measured on
2026-10-05: **48.0 MB**.

| Part | Size |
|---|---|
| `libllamakt.so`, stripped | 43.5 MB, of which 33.7 MB are the embedded Vulkan shaders |
| `classes.dex` (R8) | 2.7 MB |
| `libc++_shared.so` and the rest | ~1.8 MB |

Native libs are stored uncompressed, the Android default since API 23: the
loader maps them straight from the APK, so nothing is extracted on install.
Compressing them (`packaging.jniLibs.useLegacyPackaging = true`) would bring the
download to about 18 MB, at the cost of a 43 MB extracted copy on the device.
The download is a one-off; the copy stays. Hence the default.

The app module declares the same `ndkVersion` as llama-kt so AGP can strip the
native libs: without it the release shipped them with full debug info, 164 MB.

## Architecture

The app consumes [llama.kt](https://github.com/tensai-app/llama.kt) (Kotlin/JNI binding for llama.cpp) as a git submodule (`third_party/llama.kt`), included as the `:llama-kt` Gradle module.

## License

Apache-2.0, see [LICENSE](LICENSE).
