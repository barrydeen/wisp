import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
}

android {
    namespace = "com.wisp.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wisp.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "0.8.2"

        ndk {
            abiFilters += "arm64-v8a"
        }

        val localProps = rootProject.file("local.properties")
        val breezApiKey = if (localProps.exists()) {
            val props = Properties()
            localProps.inputStream().use { props.load(it) }
            props.getProperty("breez.api.key", "")
        } else ""
        buildConfigField("String", "BREEZ_API_KEY", "\"$breezApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.secp256k1.kmp)
    implementation(libs.secp256k1.kmp.jni.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.biometric)
    implementation(libs.splashscreen)
    implementation(libs.profileinstaller)
    implementation(libs.zxing.core)
    implementation(libs.kmp.tor.runtime)
    implementation(libs.kmp.tor.resource.exec)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.objectbox.android)
    implementation(libs.objectbox.kotlin)
    implementation(libs.breez.sdk.spark)
    implementation(libs.mlkit.barcode)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.mlkit)
}
