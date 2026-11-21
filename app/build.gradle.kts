import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The release version. versionCode grows with it (1.2.3 -> 10203), so every release installs over the one before.
val lfVersion = "8.0.9"
val lfVersionCode = lfVersion.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// A second or third LoopFollow next to the first one, each with its own settings: -PlfAppSuffix=Second gives the
// application ID com.loopandlearn.loopfollow.second. -PlfDisplayName sets the name under the app icon.
val lfAppSuffix = (findProperty("lfAppSuffix") as String?)?.trim().orEmpty()
val lfDisplayName = (findProperty("lfDisplayName") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: if (lfAppSuffix.isEmpty()) "LoopFollow" else "LoopFollow_$lfAppSuffix"
require(lfAppSuffix.matches(Regex("[A-Za-z0-9]*"))) { "lfAppSuffix may only contain letters and digits" }

android {
    namespace = "com.loopandlearn.loopfollow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.loopandlearn.loopfollow"
        minSdk = 31
        targetSdk = 35
        versionCode = lfVersionCode
        versionName = lfVersion
        if (lfAppSuffix.isNotEmpty()) applicationIdSuffix = "." + lfAppSuffix.lowercase()
        resValue("string", "app_name", lfDisplayName)
        buildConfigField("String", "APP_SUFFIX", "\"${if (lfAppSuffix.isEmpty()) "" else ".$lfAppSuffix"}\"")
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }

    // Release signing comes from the environment (LF_KEYSTORE_FILE, LF_KEYSTORE_PASSWORD, LF_KEY_ALIAS,
    // LF_KEY_PASSWORD); tools/build_release.sh fills them in from the login keychain.
    val releaseKeystore = System.getenv("LF_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("LF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LF_KEY_ALIAS") ?: "loopfollow"
                keyPassword = System.getenv("LF_KEY_PASSWORD") ?: System.getenv("LF_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // FragmentActivity from 1.3 on accepts Activity Result API request codes; biometric alone pulls in an older one.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }

    testImplementation("junit:junit:4.13.2")
}
