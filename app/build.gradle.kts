plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.hokanosekai.tensai"
    compileSdk = 35

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

    buildTypes {
        release {
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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
}
