plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tugartech.claudewalk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tugartech.claudewalk"
        // 31 (Android 12) is the floor for
        // SpeechRecognizer.createOnDeviceSpeechRecognizer.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // Matched to Kotlin 1.9.22.
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // 2024.02.01 = Compose 1.6.1 / material3 1.2.0.
    //
    // Must stay aligned with accompanist, which is built against Compose 1.6.0 and
    // pulls animation-core up to it. The previous BOM (2024.01.00) left material3
    // at 1.1.2 while animation-core resolved to 1.6.0, and material3 1.1.2 calls a
    // KeyframesSpecConfig.at() signature that 1.6.0 removed. That compiles fine and
    // only crashes when something touches the affected API, which here was the
    // spinner on the session picker: NoSuchMethodError at runtime, nothing at build
    // time. Bump both together or not at all.
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Declared explicitly so the BOM governs it rather than a transitive resolution.
    implementation("androidx.compose.animation:animation")

    // MediaSessionCompat: how earbud media-button presses reach the app.
    implementation("androidx.media:media:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
