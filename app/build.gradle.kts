import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing, kept entirely outside the repository.
//
// `keystore.properties` and the `.jks` it names are both gitignored: a signing key is the one
// thing that cannot be regenerated, because Android identifies an app by *who signed it* rather
// than by its version. Publish an update signed with a different key and every existing install
// has to be uninstalled first, taking the song folder grant, the profiles and the high scores
// with it. So the file is absent on a fresh clone by design, and its absence must not break the
// build -- somebody who has just cloned this to read the code, or to build a debug APK for their
// own television, has no reason to own a signing key.
//
// When it is missing, `release` simply has no signing config and `assembleRelease` produces an
// unsigned APK. That is a normal, working outcome for everyone except whoever publishes releases.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// **A half-written file must be as safe as a missing one.** Checking only that the file had
// *something* in it was enough to pass a `storeFile` of null straight into `rootProject.file`,
// which throws during configuration -- so a typo in a release-only file broke `assembleDebug` and
// the unit tests too, which is precisely what the comment above promises cannot happen.
//
// So all four keys have to be there and be non-blank, or there is no signing config at all. The
// file naming a keystore that does not exist is left to AGP, which says so clearly at signing
// time; this is only about not crashing before anything has been asked for.
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningKeys = requiredSigningKeys.filter { keystoreProperties.getProperty(it).isNullOrBlank() }
val canSignRelease = keystoreProperties.isNotEmpty() && missingSigningKeys.isEmpty()

// Said out loud, because somebody who wrote a `keystore.properties` meant to sign something. An
// unsigned APK appearing quietly under a name they were not expecting is the kind of silence this
// project tries not to keep -- and AGP does name the file `app-release-unsigned.apk`, so the
// warning and the output agree.
if (keystoreProperties.isNotEmpty() && !canSignRelease) {
    logger.warn(
        "keystore.properties is missing ${missingSigningKeys.joinToString()} -- " +
            "the release build will be unsigned."
    )
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

    signingConfigs {
        // Only declared when every key it needs is actually there; see the note above.
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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