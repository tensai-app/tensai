# Contributing to tensai (the app)

Kotlin + Compose, package `io.github.tensaiapp`, arm64-v8a, minSdk 29. The org-wide entry point, issue forms and security policy are in [tensai-app/.github](https://github.com/tensai-app/.github). This file is the app's side: build, checks, commits.

## Test on your phone (no code)

The most useful report is numbers from a phone that is not the test device. Settings > This device > Benchmark, Copy, then open a [Device report](https://github.com/tensai-app/tensai/issues/new?template=device_report.yml). The app sends nothing: you paste what you choose.

### What the benchmarks measure

Release builds, Settings > This device > Benchmark (`app/src/main/kotlin/io/github/tensaiapp/bench/DeviceBench*.kt`):

- runs on the model already loaded, no tools, thinking off, no network;
- one warm-up decode, then one decode of 64 tokens at each of 256, 1024 and 2048 tokens of context (those that fit the loaded context), each prompt prefilled from an almost empty cache;
- per size: prefill tok/s, decode tok/s, time to the first token;
- the open conversation's session is saved before the run so the cache can be overwritten, and the cache is left restorable after;
- Stop cuts it short, the sizes already measured make the report. The screen stays on while it runs, the phone may warm up;
- nothing is stored or sent. The report is plain text with fixed English labels, in the order of the Device report form: keep that order if you touch `DeviceReport`.

Debug builds only (built from source, not published):

- Settings > Bench has a decode curve (speed against context already in the KV cache, 0 to 4096 tokens, more points and options than the release one) and the agent bench;
- the agent bench runs the prompts of `app/src/debug/assets/bench/agent-prompts.json` through the chat's agent loop on the loaded model and grades whether the model calls a tool it should not, or misses one. It can also be started with `adb shell am start ... --es bench agent` (see the KDoc of `AgentBench`). Runs are kept on the device in debug builds only, and logged on the `tensai.bench` tag.

### How results are used

Nothing adapts at runtime from a benchmark: the app does not measure your phone and tune itself. The numbers are for the maintainer:

- the fit rating (Runs well / Tight fit / Won't fit / Unsupported, `compat/CompatibilityChecker.kt`) is decided from RAM against the planned load (weights, KV cache, compute buffers) and from rules calibrated on measured tok/s of one device: models of 3.5B parameters or more and the gemma3n/gemma4 architectures are rated Tight fit for speed, since no benchmark is available when a model is rated;
- the default backend per architecture (CPU for dense models, GPU for gemma3n, gemma4, phi, nemotron_h, when Vulkan exists) comes from the same table;
- reports from other SoCs are what can change those constants and the per-architecture table. A rule that is wrong on your phone is a bug worth filing, with the benchmark attached.

## Build

Requirements: JDK 21, Android SDK (platform 35), NDK 27.2, CMake, `g++`, `glslc`.

```bash
git clone --recurse-submodules https://github.com/tensai-app/tensai.git
cd tensai
./third_party/llama.kt/scripts/build-opencl.sh
./third_party/llama.kt/scripts/gen-vulkan-shaders.sh "$(which glslc)"
./gradlew :app:assembleDebug
```

The two scripts generate native artifacts a fresh clone lacks, once; details and timings are in the [README](README.md). The debug build installs next to the release as "tensai dev" (`io.github.tensaiapp.debug`), with its own data: test with it, not with an app that holds real chats.

`third_party/llama.kt` is a git submodule (the `:llama-kt` module). A change to the inference layer goes to [llama.kt](https://github.com/tensai-app/llama.kt) first; the app's pointer is bumped afterwards by the maintainer.

## Checks before a PR

```bash
./gradlew :websearch:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:minifyReleaseWithR8
```

- One Gradle run at a time: the native build is heavy.
- CI on pull requests runs `:websearch:test :app:testDebugUnitTest` and compiles the instrumented test sources. The native build runs when native paths change. The lint, assemble and R8 steps need the native artifacts above, so they are yours to run locally.
- Native code changed: also run `third_party/llama.kt/scripts/test-kv-reuse.sh` and `test-vk-split.sh`.
- Never run the instrumented suite (`connectedCheck`) on a phone that holds models: it wipes them. CI does not run it either, see [docs/ci.md](docs/ci.md).

## Commits and PRs

- Conventional Commits: `type(scope): subject`. Types: feat, fix, perf, refactor, docs, test, build, ci, chore, revert, style. Scope is the area (`bench`, `engine`, `download`...), look at `git log`.
- No issue or ticket id in the subject or body. Link the issue in the PR description.
- Fork, branch from `main`, PR against `main`. Describe what you ran and, for UI or native changes, the device you tried it on.
- Match the surrounding code: comment density, KDoc style, naming. Comments say why, not what.
- Strings: user-facing text lives in `strings.xml`, not in Kotlin.
- Apache-2.0, see [LICENSE](LICENSE).
