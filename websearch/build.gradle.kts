plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    // jsoup's nullness annotations, `provided` in its POM. Kotlin 2.4 refuses an inferred type whose
    // annotation class is missing from the classpath; compile time only, nothing ships.
    compileOnly(libs.jspecify)

    testImplementation(libs.junit)
}
