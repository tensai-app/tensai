# tensai

[![CI](https://github.com/hokanosekai/tensai/actions/workflows/ci.yml/badge.svg)](https://github.com/hokanosekai/tensai/actions/workflows/ci.yml)

天才 — your phone, your AI. Open-source Android app that turns a phone into a local AI agent: runs any GGUF model fully on-device ([llama.kt](https://github.com/hokanosekai/llama.kt) / llama.cpp), chat UI, and later a LAN API server and phone actions.

**Status: early development.** Nothing usable yet.

## Build from source

Requirements: JDK 21, Android SDK (platform 35), NDK 27.2, CMake, a host C++
compiler (`g++`), and `glslc` (shaderc, packaged with the Vulkan SDK or as
`shaderc` on most distros).

```bash
git clone --recurse-submodules git@github.com:hokanosekai/tensai.git
cd tensai
```

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
full re-vendoring of `cpp/`; if you only want to build, its Vulkan section on
its own is enough:

```bash
KT=third_party/llama.kt
SRC=$KT/third_party/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders
OUT=$KT/llama-kt/src/main/cpp/ggml-vulkan
mkdir -p /tmp/vk-spv /tmp/vk-out "$OUT/shaders"

cmake -S "$SRC" -B /tmp/vk-gen -DCMAKE_BUILD_TYPE=Release -Wno-dev
cmake --build /tmp/vk-gen -j"$(nproc)"

/tmp/vk-gen/vulkan-shaders-gen \
  --output-dir /tmp/vk-spv --target-hpp /tmp/vk-out/ggml-vulkan-shaders.hpp

for comp in "$SRC"/*.comp; do
  /tmp/vk-gen/vulkan-shaders-gen \
    --glslc "$(which glslc)" --source "$comp" --output-dir /tmp/vk-spv \
    --target-hpp /tmp/vk-out/ggml-vulkan-shaders.hpp \
    --target-cpp "/tmp/vk-out/$(basename "$comp").cpp"
done

cp /tmp/vk-out/ggml-vulkan-shaders.hpp "$OUT/"
for f in /tmp/vk-out/*.comp.cpp; do
  sed 's|#include "ggml-vulkan-shaders.hpp"|#include "../ggml-vulkan-shaders.hpp"|g' \
    "$f" > "$OUT/shaders/$(basename "$f")"
done
```

Measured: 70 s on 22 cores, 106 s on 4, peaking around 1.1 GB resident across 16
concurrent `glslc`, and 250 MB of intermediates under `/tmp`. The output is
byte-for-byte deterministic for a given `llama.cpp` commit and `glslc` build, so
it caches well. A different `glslc` version yields valid but different SPIR-V.

Then:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI does the same thing, in `.github/workflows/native.yml`: it regenerates both
and caches the shaders. Keep that workflow and the snippet above in step with
each other, see [docs/ci.md](docs/ci.md).

Target hardware: arm64-v8a only, minSdk 29.

## Architecture

The app consumes [llama.kt](https://github.com/hokanosekai/llama.kt) (Kotlin/JNI binding for llama.cpp) as a git submodule (`third_party/llama.kt`), included as the `:llama-kt` Gradle module.

## License

Apache 2.0 — see [LICENSE](LICENSE).
