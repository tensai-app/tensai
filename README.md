# tensai

[![CI](https://github.com/hokanosekai/tensai/actions/workflows/ci.yml/badge.svg)](https://github.com/hokanosekai/tensai/actions/workflows/ci.yml)

天才 — your phone, your AI. Open-source Android app that turns a phone into a local AI agent: runs any GGUF model fully on-device ([llama.kt](https://github.com/hokanosekai/llama.kt) / llama.cpp), chat UI, and later a LAN API server and phone actions.

**Status: early development.** Nothing usable yet.

## Build from source

Requirements: JDK 21, Android SDK (platform 35), NDK 27.2, CMake.

```bash
git clone --recurse-submodules git@github.com:hokanosekai/tensai.git
cd tensai

# One-time: build the OpenCL ICD loader used by the llama.kt GPU backend.
./third_party/llama.kt/scripts/build-opencl.sh

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `cpp/` sources are vendored, but two things the native build links against are
not in git and have to be generated locally: `libOpenCL.so` (the command above)
and the Vulkan SPIR-V shaders, which come from llama.kt's `scripts/bootstrap.sh`.
Without them `assembleDebug` stops on `ggml-vulkan-shaders.hpp file not found`.
This is also what keeps the build out of CI — see [docs/ci.md](docs/ci.md).

Target hardware: arm64-v8a only, minSdk 29.

## Architecture

The app consumes [llama.kt](https://github.com/hokanosekai/llama.kt) (Kotlin/JNI binding for llama.cpp) as a git submodule (`third_party/llama.kt`), included as the `:llama-kt` Gradle module.

## License

Apache 2.0 — see [LICENSE](LICENSE).
