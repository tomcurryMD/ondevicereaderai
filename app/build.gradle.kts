plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.readertomeai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.readertomeai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    /*
     * ABI splits — produces one APK per architecture.
     * For Play Store: publish as AAB (./gradlew :app:bundleRelease).
     * For local testing: ./gradlew :app:assembleDebug
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude duplicate XML parser classes that conflict with Android's built-in
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Exclude kxml2/xmlpull globally — Android has these built in and they cause
// R8 "duplicate class" errors during release builds
configurations.all {
    exclude(group = "xmlpull", module = "xmlpull")
    exclude(group = "net.sf.kxml", module = "kxml2")
    exclude(group = "xpp3", module = "xpp3")
    exclude(group = "xpp3", module = "xpp3_min")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ePub parsing — exclude XML parser libs that duplicate Android builtins
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("nl.siegmann.epublib:epublib-core:3.1") {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
        exclude(group = "net.sf.kxml")
        exclude(group = "xpp3")
    }
    implementation("org.slf4j:slf4j-android:1.7.36")

    // PDF rendering
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Sherpa-ONNX for Piper TTS
    implementation(files("libs/sherpa-onnx-1.13.0.aar"))

    // Archive extraction for TTS model downloads
    implementation("org.apache.commons:commons-compress:1.27.1")

    // WebView
    implementation("androidx.webkit:webkit:1.12.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media session for TTS notification controls
    implementation("androidx.media:media:1.7.0")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Accompanist
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.36.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
