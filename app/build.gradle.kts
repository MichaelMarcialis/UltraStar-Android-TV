plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ultrastarandroidtv"
    ndkVersion = "30.0.15729638"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ultrastarandroidtv"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Shield is arm64-only, so don't spend build time on other ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    // org.json is in the Android framework, so the app itself needs no JSON dependency -- but the
    // framework copy is stubbed in JVM unit tests. This is that same AOSP implementation extracted
    // from the SDK (Apache-2.0, Maven Central), test-only, so the tests parse exactly what the
    // device parses without adding a runtime dependency.
    testImplementation(libs.android.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // Compose for TV
    implementation(libs.androidx.tv.material)

    // Playback. Media3 is Apache-2.0 and comes from Google's Maven, so it satisfies the
    // permissive-licence and no-custom-repository rules this project holds itself to.
    implementation(libs.androidx.media3.exoplayer)
}