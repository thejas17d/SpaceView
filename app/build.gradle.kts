plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Reads a value from -P / gradle.properties first, then the environment (used by CI secrets). */
fun config(name: String): String? =
    (providers.gradleProperty(name).orNull ?: System.getenv(name))?.takeIf { it.isNotBlank() }

// Google's public test ad IDs (always used by debug builds). Release builds use the real IDs when
// all of ADMOB_APP_ID / ADMOB_BANNER_ID / ADMOB_INTERSTITIAL_ID are set (gradle property or env var).
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerId = "ca-app-pub-3940256099942544/9214589741"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"

val releaseAds = listOf("ADMOB_APP_ID", "ADMOB_BANNER_ID", "ADMOB_INTERSTITIAL_ID").associateWith { config(it) }
val hasRealAds = releaseAds.values.all { it != null }
fun releaseAd(name: String, test: String): String = if (hasRealAds) releaseAds.getValue(name)!! else test

// A keystore without its passwords/alias would only fail late, inside packageRelease, with an
// unclear signing error. Fail at configuration time instead, on every build that has a keystore.
val keystoreSecrets = listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
val missingKeystoreSecrets = keystoreSecrets.filter { config(it) == null }
if (config("KEYSTORE_FILE") != null && missingKeystoreSecrets.isNotEmpty()) {
    throw GradleException("A release keystore is configured but ${missingKeystoreSecrets.joinToString(", ")} is missing")
}

// Store builds (CI sets REQUIRE_RELEASE_CONFIG=true on version tags) must never ship test ads,
// a debug signature or a placeholder support address.
if (config("REQUIRE_RELEASE_CONFIG") == "true") {
    val missing = buildList {
        if (!hasRealAds) add("ADMOB_APP_ID / ADMOB_BANNER_ID / ADMOB_INTERSTITIAL_ID")
        if (config("KEYSTORE_FILE") == null) add("KEYSTORE_BASE64")
        addAll(missingKeystoreSecrets)
        if (config("SUPPORT_EMAIL") == null) add("SUPPORT_EMAIL")
        if (config("PRIVACY_POLICY_URL") == null) add("PRIVACY_POLICY_URL")
    }
    if (missing.isNotEmpty()) throw GradleException("Release build is missing: ${missing.joinToString(", ")}")
}

android {
    namespace = "com.spaceview.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spaceview.app"
        // ARCore "AR Required" minimum.
        minSdk = 24
        targetSdk = 36
        versionCode = (config("VERSION_CODE") ?: "1").toInt()
        versionName = config("VERSION_NAME") ?: "0.1.0"
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${config("PRIVACY_POLICY_URL") ?: ""}\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"${config("SUPPORT_EMAIL") ?: ""}\"")

        // ARCore is only certified on ARM phones. x86/x86_64 would only bloat the universal APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    androidResources {
        // English + Hindi for v1; strips other locales pulled in by libraries.
        localeFilters += listOf("en", "hi")
    }

    signingConfigs {
        create("release") {
            val store = config("KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = config("KEYSTORE_PASSWORD")
                keyAlias = config("KEY_ALIAS")
                keyPassword = config("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "AD_BANNER", "\"$testBannerId\"")
            buildConfigField("String", "AD_INTERSTITIAL", "\"$testInterstitialId\"")
            buildConfigField("boolean", "TEST_ADS", "true")
        }
        release {
            // R8 full mode is the AGP default; never set android.enableR8.fullMode=false.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (config("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                // No release keystore configured: sign with the debug key so the APK still installs for testing.
                signingConfigs.getByName("debug")
            }
            manifestPlaceholders["admobAppId"] = releaseAd("ADMOB_APP_ID", testAdmobAppId)
            buildConfigField("String", "AD_BANNER", "\"${releaseAd("ADMOB_BANNER_ID", testBannerId)}\"")
            buildConfigField("String", "AD_INTERSTITIAL", "\"${releaseAd("ADMOB_INTERSTITIAL_ID", testInterstitialId)}\"")
            buildConfigField("boolean", "TEST_ADS", (!hasRealAds).toString())
        }
    }

    compileOptions {
        // SceneView 4.65 is compiled for JVM 21.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Uncompressed, page-aligned native libs: required for 16 KB page-size support and avoids
        // an extracted copy on disk. Play compresses them over the wire.
        jniLibs.useLegacyPackaging = false
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
        )
    }

    dependenciesInfo {
        // Play reads dependency metadata from the AAB; it is dead weight inside the APK.
        includeInApk = false
        includeInBundle = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "ObsoleteLintCustomCheck")
        // Deliberate: Play requires target 36 today (37 is planned before Aug 2027), and ARCore
        // ships ARM-only binaries, so x86_64/ChromeOS builds are out of scope.
        disable += setOf("OldTargetApi", "ChromeOsAbiSupport")
    }
}

configurations.configureEach {
    // filamat is Filament's runtime material compiler (~12 MB). SpaceView ships precompiled
    // .filamat materials only, so the compiler must never reach the APK.
    exclude(group = "com.google.android.filament", module = "filamat-android")
    exclude(group = "com.google.android.filament", module = "filamat-android-lite")
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":ar"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // M5 (Builder B): settings + ad counters, AdMob + UMP consent.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.ads)
    implementation(libs.ump)

    // M2 (Builder A): navigation host, ViewModels for the measure / history features.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
