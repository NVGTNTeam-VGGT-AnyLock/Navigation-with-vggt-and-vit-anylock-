import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
}

// ── Load local.properties (gitignored, never committed) ──────────────
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY")
    ?: error("MAPS_API_KEY not found in local.properties. " +
        "Add MAPS_API_KEY=YOUR_KEY to mobile/android/local.properties")

android {
    namespace = "com.navisense"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.navisense"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject Maps API key into AndroidManifest.xml via placeholder
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        /**
         * Backend URL for the NaviSense positioning API.
         * Default: 10.0.2.2 is the host machine loopback from Android emulator.
         * For physical device USB debugging, change to your machine's local IP,
         * e.g., "http://192.168.1.100:8000/"
         */
        buildConfigField("String", "BACKEND_URL", "\"http://10.0.2.2:8000/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    // ── Native Library Packaging ─────────────────────────────────────
    // Android 15 (API 35) enforces 16 KB page sizes. CameraX 1.4.0+
    // ships 16 KB-aligned native .so files, but cached builds may still
    // contain 4 KB-aligned libraries. This block forces extraction
    // (instead of mmap), bypassing the alignment check at runtime.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // ── AndroidX Core ──────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // ── Lifecycle / ViewModel ──────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.7.0")

    // ── Navigation Component ───────────────────────────────────────
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // ── Google Maps SDK ────────────────────────────────────────────
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-utils-ktx:5.0.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // ── Coroutines ─────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ── Networking (Retrofit + OkHttp) ─────────────────────────────
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── CameraX (16KB page-size safe — v1.4.0+) ────────────────────
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ── Image Loading (Coil) ───────────────────────────────────────
    implementation("io.coil-kt:coil:2.5.0")

    // ── Room Database (SQLite) ─────────────────────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── Testing ────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
