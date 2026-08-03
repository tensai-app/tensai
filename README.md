# tensai

天才 — your phone, your AI. Open-source Android app that turns a phone into a local AI agent: runs any GGUF model fully on-device ([llama.kt](https://github.com/hokanosekai/llama.kt) / llama.cpp), chat UI, and later a LAN API server and phone actions.

**Status: early development.** Nothing usable yet.

## Build from source

Requirements: JDK 21, Android SDK (platform 35), NDK 27.2, CMake.

```bash
git clone --recurse-submodules git@github.com:hokanosekai/tensai.git
cd tensai

# One-time: build the OpenCL ICD loader used by the llama.kt GPU backend.
# Do NOT run llama.kt's bootstrap.sh — the C++ sources are already vendored;
# bootstrap is only for maintainers bumping the llama.cpp submodule.
./third_party/llama.kt/scripts/build-opencl.sh

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Target hardware: arm64-v8a only, minSdk 29.

## Architecture

The app consumes [llama.kt](https://github.com/hokanosekai/llama.kt) (Kotlin/JNI binding for llama.cpp) as a git submodule (`third_party/llama.kt`), included as the `:llama-kt` Gradle module.

## License

Apache 2.0 — see [LICENSE](LICENSE).
