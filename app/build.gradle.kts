import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
}

// local.properties is gitignored and per-machine. It already carries the Breez
// and Giphy API keys; the release signing credentials below read from the same
// place. Nothing in it is ever committed.
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
}

/** local.properties wins over the environment. Blank is treated as absent. */
fun secret(propertyKey: String, envKey: String): String? =
    (localProps.getProperty(propertyKey) ?: System.getenv(envKey))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

fun expandHome(path: String): File =
    if (path.startsWith("~/")) File(System.getProperty("user.home"), path.removePrefix("~/"))
    else File(path)

// ---------------------------------------------------------------------------
// Release signing
//
// Credentials come from local.properties, or the matching ZAPCOOKING_*
// environment variables for a machine that has no local.properties. The
// keystore file itself lives outside the repo and is never committed. Which
// keystore to use — and why the Zapstore one is irreplaceable — is
// ZAPCOOKING_ANDROID_BUILD.md §"Release keystore".
//
// All four values are required together, and a release artifact cannot be
// built without them. AGP does NOT fall back to the debug key when a release
// signingConfig is absent: it silently emits app-<flavor>-release-unsigned.apk
// and an unsigned .aab. Play rejects those at upload, and an APK signed by the
// wrong key cannot update an existing Zapstore install and breaks Google
// sign-in. Failing here is cheaper than finding out at the Console.
// ---------------------------------------------------------------------------
val signingInputs = mapOf(
    "zapcooking.keystore.path" to secret("zapcooking.keystore.path", "ZAPCOOKING_KEYSTORE_PATH"),
    "zapcooking.keystore.password" to secret("zapcooking.keystore.password", "ZAPCOOKING_KEYSTORE_PASSWORD"),
    "zapcooking.key.alias" to secret("zapcooking.key.alias", "ZAPCOOKING_KEY_ALIAS"),
    "zapcooking.key.password" to secret("zapcooking.key.password", "ZAPCOOKING_KEY_PASSWORD"),
)
val missingSigningInputs = signingInputs.filterValues { it == null }.keys
val releaseSigningConfigured = missingSigningInputs.isEmpty()

// Some-but-not-all means someone meant to sign and mistyped a key. Never let
// that degrade into an unsigned build, whatever task is being run.
if (!releaseSigningConfigured && missingSigningInputs.size < signingInputs.size) {
    error(
        "Incomplete release signing credentials — missing ${missingSigningInputs.joinToString()}. " +
            "Set all four in local.properties (or all four ZAPCOOKING_* environment variables), " +
            "or none. See ZAPCOOKING_ANDROID_BUILD.md §\"Release signing\"."
    )
}

val releaseKeystore = signingInputs.getValue("zapcooking.keystore.path")?.let(::expandHome)
if (releaseKeystore != null && !releaseKeystore.isFile) {
    error(
        "Release keystore not found at ${releaseKeystore.absolutePath} " +
            "(zapcooking.keystore.path). The keystore is not stored in this repo."
    )
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
        versionCode = 90
        versionName = "1.3.6"
        resValue("string", "app_name", "Zap Cooking")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        val breezApiKey = localProps.getProperty("breez.api.key", "")
        val giphyApiKey = localProps.getProperty("giphy.api.key", "")
        buildConfigField("String", "BREEZ_API_KEY", "\"$breezApiKey\"")
        buildConfigField("String", "BREEZ_SDK_VERSION", "\"${libs.versions.breez.sdk.spark.get()}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"$giphyApiKey\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingInputs.getValue("zapcooking.keystore.password")
                keyAlias = signingInputs.getValue("zapcooking.key.alias")
                keyPassword = signingInputs.getValue("zapcooking.key.password")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            resValue("string", "app_name", "Zap Cooking Debug")
        }

        release {
            // Null when credentials are absent; the release-packaging guards
            // below stop the build before an unsigned artifact can be produced.
            signingConfig = signingConfigs.findByName("release")
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

// Refuse to produce a release artifact that nobody can ship.
//
// Prefer task-local doFirst guards over gradle.taskGraph.whenReady — the latter
// is incompatible with the configuration cache. A config-time check on the
// explicitly requested task names covers the common case immediately (so we
// don't burn an R8 cycle first); doFirst still catches packaging tasks reached
// only as dependencies. Debug builds, unit tests and lint are unaffected.
if (!releaseSigningConfigured) {
    val missingCredentialsMessage =
        "no release signing credentials. " +
            "Set zapcooking.keystore.path, zapcooking.keystore.password, " +
            "zapcooking.key.alias and zapcooking.key.password in local.properties " +
            "(or the matching ZAPCOOKING_* environment variables). " +
            "See ZAPCOOKING_ANDROID_BUILD.md §\"Release signing\"."

    fun isReleasePackagingTask(taskName: String): Boolean {
        val n = taskName.substringAfterLast(':')
        return n.contains("Release") &&
            (n.startsWith("assemble") || n.startsWith("bundle") || n.startsWith("package"))
    }

    // Aggregates that pull in every variant's packaging tasks.
    fun isReleasePullingAggregate(taskName: String): Boolean {
        val n = taskName.substringAfterLast(':')
        return n == "build" || n == "assemble" || n == "bundle" ||
            n == "assembleRelease" || n == "bundleRelease"
    }

    val requested = gradle.startParameter.taskNames
    val blockedRequest = requested.firstOrNull {
        isReleasePackagingTask(it) || isReleasePullingAggregate(it)
    }
    if (blockedRequest != null) {
        error(
            "Cannot build a release artifact (requested task $blockedRequest): " +
                missingCredentialsMessage
        )
    }

    tasks.configureEach {
        if (isReleasePackagingTask(name)) {
            doFirst {
                error("Cannot build a release artifact (task $name): $missingCredentialsMessage")
            }
        }
    }
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

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
