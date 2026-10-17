pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tensai"

include(":app")

// llama.kt is consumed as a git submodule (see third_party/llama.kt).
include(":llama-kt")
project(":llama-kt").projectDir = file("third_party/llama.kt/llama-kt")

// Pure Kotlin/JVM search providers and page reader (TEN-180), meant to be extracted later.
include(":websearch")
