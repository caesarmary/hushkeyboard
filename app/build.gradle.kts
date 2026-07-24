import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing credentials live in keystore.properties (gitignored, never
// committed — see SECURITY.md rule 9). The file won't exist on a fresh clone
// or CI, so release/hardened builds fall back to debug signing in that case;
// that fallback is for build-ability only, not for shipping an unsigned-for-prod APK.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.hushkeyboard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hushkeyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Compile-time switch for the learned-words dictionary (SECURITY.md rule 5,
        // Phase 4). Default ON for the standard build. The "hardened" build type
        // below overrides this to false, compiling the feature out entirely so the
        // hardened variant never even contains the dictionary-write code path.
        // Read in code as BuildConfig.LEARNED_WORDS_ENABLED.
        buildConfigField("boolean", "LEARNED_WORDS_ENABLED", "true")

        // Compile-time switch for Phase 5 context-aware autocorrect rescoring
        // (slice 1b). Default ON for the standard build; the "hardened" build
        // below overrides this to false, compiling the rescorer's async
        // model-scoring refine out entirely (the synchronous edit-distance-1
        // autocorrect still works either way). Read as
        // BuildConfig.AUTOCORRECT_RESCORE_ENABLED.
        buildConfigField("boolean", "AUTOCORRECT_RESCORE_ENABLED", "true")

        // Phase 4 Session 53: llama.cpp runtime-swap smoke test. Only the arm64
        // ABI is built/shipped (the only ABI the S52 cross-compile targeted and
        // the only one the test device, an A52s, needs).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // BuildConfig is Android's own generated constants class — no dependency, no
    // permission. Enabled so the compile-time flag above is available in code.
    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // Phase 4 Session 56: the GGUF is copied byte-for-byte from assets to
        // private storage before llama.cpp mmaps it by path (see
        // LlamaPredictorDeviceTest) -- a compressed asset would copy out as
        // garbage, so it must stay uncompressed in the APK too.
        noCompress += "gguf"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Offline release key (SECURITY.md rule 9), loaded from the gitignored
            // keystore.properties. Falls back to debug signing only when that file
            // is absent (fresh clone / CI), so the build still runs end to end.
            signingConfig = if (keystoreProperties.containsKey("storeFile")) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // The hardened build variant. Identical to release, except the learned-words
        // dictionary is compiled out (LEARNED_WORDS_ENABLED = false). Build it with
        // `./gradlew assembleHardened`. Per the Phase 4 design, a compile-time flag
        // disables the learned-words feature entirely for the hardened build variant.
        create("hardened") {
            initWith(getByName("release"))
            buildConfigField("boolean", "LEARNED_WORDS_ENABLED", "false")
            // Phase 5 slice 1b: the hardened variant omits the async model-scoring
            // autocorrect refine too. The synchronous ed-1 autocorrect is unaffected.
            buildConfigField("boolean", "AUTOCORRECT_RESCORE_ENABLED", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}