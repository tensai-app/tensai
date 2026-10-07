plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.hokanosekai.tensai"
    compileSdk = 35
    // Same NDK as llama-kt. The app module packages the native libs, and AGP strips them with
    // its own module's NDK: without this it looked for its default NDK, which isn't installed,
    // logged "Unable to strip ... packaging them as they are" and shipped libllamakt.so with
    // full debug info, 158 MB instead of 43 MB (TEN-41).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.hokanosekai.tensai"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // On-device inference only makes sense on modern arm64 hardware.
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing is fed ONLY by environment variables (CI decodes the keystore into
    // $RUNNER_TEMP, see .github/workflows/release.yml). Nothing is read from the repo. When any
    // variable is missing the release build type stays unsigned, exactly as a local
    // `assembleRelease` always was.
    val releaseKeystorePath = System.getenv("TENSAI_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("TENSAI_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("TENSAI_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("TENSAI_KEY_PASSWORD")
    val releaseSigningVars = mapOf(
        "TENSAI_KEYSTORE_PATH" to releaseKeystorePath,
        "TENSAI_KEYSTORE_PASSWORD" to releaseKeystorePassword,
        "TENSAI_KEY_ALIAS" to releaseKeyAlias,
        "TENSAI_KEY_PASSWORD" to releaseKeyPassword,
    )
    val releaseSigningMissing = releaseSigningVars.filterValues { it.isNullOrEmpty() }.keys
    // 0 set: unsigned. 4 set: signed. Anything in between is a misconfiguration that would
    // otherwise silently ship an unsigned APK, so it fails the build.
    if (releaseSigningMissing.isNotEmpty() && releaseSigningMissing.size < releaseSigningVars.size) {
        throw GradleException(
            "Incomplete release signing environment, missing: ${releaseSigningMissing.joinToString()}",
        )
    }
    val releaseSigningAvailable = releaseSigningMissing.isEmpty()

    if (releaseSigningAvailable) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // Room's MigrationTestHelper reads the exported schema JSONs (see the `ksp { arg(...) }`
        // block below) as test assets to build a real historical database to migrate from.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

kotlin {
    jvmToolchain(21)
}

ksp {
    // Committed under app/schemas/ (see TensaiDb's KDoc) so MigrationTestHelper can replay every
    // past version and TEN-48's migration tests have something to diff future schema changes
    // against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":llama-kt"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // SamplingJson uses org.json, which lives in the Android framework and is therefore a stub
    // that throws in JVM unit tests. This is the real implementation, test-only: it puts a
    // working org.json ahead of the mockable android.jar on the unit-test classpath, so the
    // persistence format's round trip and its tolerance of malformed input can be tested
    // without a device (TEN-35). Nothing ships with it — on device the framework's own copy is
    // what runs.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
}
