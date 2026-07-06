import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
}

android {
    val baseApplicationId = rootProject.extra["baseApplicationId"] as String
    val debugApplicationIdSuffix = rootProject.extra["debugApplicationIdSuffix"] as String

    namespace = "cooking.zap.app"
    compileSdk = 35

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = 82
        versionName = "1.2.0"
        resValue("string", "app_name", "Zap Cooking")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        val localProps = rootProject.file("local.properties")
        val props = if (localProps.exists()) {
            Properties().apply { localProps.inputStream().use { load(it) } }
        } else Properties()
        val breezApiKey = props.getProperty("breez.api.key", "")
        val giphyApiKey = props.getProperty("giphy.api.key", "")
        buildConfigField("String", "BREEZ_API_KEY", "\"$breezApiKey\"")
        buildConfigField("String", "BREEZ_SDK_VERSION", "\"${libs.versions.breez.sdk.spark.get()}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"$giphyApiKey\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            resValue("string", "app_name", "Zap Cooking Debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Distribution targets (ZAPCOOKING_ANDROID_BUILD.md §2). Zapstore is
    // primary, Play secondary. applicationId (cooking.zap.app) and the
    // debug suffix are shared.
    //
    // MEMBERSHIP_LINKOUT_ENABLED (Sous Chef, Phase 3): whether tapping a
    // members-only import as a non-member may open the external
    // zap.cooking/membership page. True for both stores today; the Play
    // flavor can flip to false without code changes if Play's external-
    // purchase policy ever requires it (false shows an informational
    // dialog instead).
    flavorDimensions += "store"
    productFlavors {
        create("zapstore") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "MEMBERSHIP_LINKOUT_ENABLED", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "MEMBERSHIP_LINKOUT_ENABLED", "true")
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
        jniLibs.useLegacyPackaging = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented (connected) tests only — e.g. the live NIP-98 round-trip.
    // Not on the hermetic :app:testDebugUnitTest classpath.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

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
    implementation(libs.media3.session)
    implementation(libs.biometric)
    implementation(libs.splashscreen)
    implementation(libs.profileinstaller)
    implementation(libs.zxing.core)
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
}
