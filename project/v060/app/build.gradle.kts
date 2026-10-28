plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.voicedock.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicedock.ai"
        // 26 = Android 8.0: TYPE_APPLICATION_OVERLAY, notification channels, isShowingHintText
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Offline Whisper (whisper.cpp) is built for 64-bit ARM only — practically every phone from the last years.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                // Cap parallel C++ compiles (the build machine/CI may have little RAM).
                // NDK r27 needs this opt-in for 16 KB-page-size arm64 devices.
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_JOB_POOLS=compile=1", "-DCMAKE_JOB_POOL_COMPILE=compile")
            }
        }
    }
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Fixed DEBUG signing key (committed on purpose, debug-only, public password "android"): every build can
    // update the previous one on the phone, and the SHA-1 registered for Google OAuth stays the same.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Keep Robolectric workers bounded and release their memory between test classes.
        unitTests.all { it.maxHeapSize = "512m"; it.forkEvery = 1 }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/DEPENDENCIES")
        resources.pickFirsts += setOf("META-INF/mailcap", "META-INF/mailcap.default", "META-INF/mimetypes.default", "META-INF/javamail.default.providers", "META-INF/javamail.default.address.map", "META-INF/javamail.charset.map")
    }
}

ksp {
    // Room schema history is committed so every future migration can be verified.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Knowledge import: local PDF text extraction (Apache-2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Gmail over IMAP with an app password (read-only folder access)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    // Google OAuth (AuthorizationClient) for read-only Gmail/Drive scopes
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
