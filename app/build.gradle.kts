import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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

// A non-empty but wrong PLAY_LICENSE_KEY would let users pay, fail every signature check and get refunded
// after 3 days. Any build that is given a key must get a parsable one: base64 X.509 RSA, 2048+ bits (Play's
// licence keys). That it is *this app's* key is checked by the release-gate licence-tester purchase
// (docs/TESTING.md §17). P1 review MINOR 5.
config("PLAY_LICENSE_KEY")?.let { key ->
    val valid = runCatching {
        val der = Base64.getDecoder().decode(key.filterNot(Char::isWhitespace))
        val rsa = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        rsa.modulus.bitLength() >= 2048
    }.getOrDefault(false)
    if (!valid) {
        throw GradleException(
            "PLAY_LICENSE_KEY is not a base64 X.509 RSA public key of 2048+ bits " +
                "(copy it from Play Console > Monetisation setup > Licensing)",
        )
    }
}

// A malformed support address or policy URL would build fine and then silently disappear from the app: AppLinks
// shows only an https URL and a plausible address. Any build given one checks it with the same rules (SM1-M5 review
// MINOR 13; keep in sync with AppLinks.isPlausibleEmail / privacyPolicyUrl).
config("SUPPORT_EMAIL")?.trim()?.let { email ->
    val plausible = email.length in 6..254 && email.count { it == '@' } == 1 &&
        email.substringAfter('@').contains('.') && email.none { it.isWhitespace() || it == '"' }
    if (!plausible) throw GradleException("SUPPORT_EMAIL is not a valid e-mail address: '$email'")
}
config("PRIVACY_POLICY_URL")?.trim()?.let { url ->
    val valid = url.startsWith("https://") && url.length > "https://".length + 3 && url.none { it.isWhitespace() || it == '"' }
    if (!valid) throw GradleException("PRIVACY_POLICY_URL must be an https:// URL, got '$url'")
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
        // Without the Play licence key no purchase can be verified, so Pro could never be granted.
        if (config("PLAY_LICENSE_KEY") == null) add("PLAY_LICENSE_KEY")
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
        // Play Console -> Monetisation setup -> Licensing: base64 RSA public key used by PurchaseVerifier
        // (ADR-004). Not secret, but kept out of the public repo; a CI secret / gradle property. Blank =
        // purchases refused (fail closed).
        buildConfigField("String", "PLAY_LICENSE_KEY", "\"${config("PLAY_LICENSE_KEY")?.filterNot(Char::isWhitespace) ?: ""}\"")

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
            // Debug builds sell through FakeBillingGateway (no Play Console needed) unless -PFAKE_BILLING=false.
            buildConfigField("boolean", "FAKE_BILLING", (config("FAKE_BILLING") != "false").toString())
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
            // Release always uses Google Play Billing.
            buildConfigField("boolean", "FAKE_BILLING", "false")
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

    // P1 (Builder B): Play Billing 9.1 (ARCHITECTURE-v2 §4); lifecycle-process for foreground refreshes
    // (already on the release classpath through other AndroidX libraries; declared because it is used).
    implementation(libs.billing.ktx)
    implementation(libs.androidx.lifecycle.process)

    // M5 remainder (Builder B): Play In-App Review (review 2.0.2 + core-common 2.0.4, ~38 KB of classes before R8).
    implementation(libs.play.review)

    // SM1b (Builder A): object-name suggestions at scan Review. The *unbundled* ML Kit image labeller: Play services
    // downloads the model (manifest meta-data "ica"), so the APK carries only the thin client.
    implementation(libs.play.services.mlkit.image.labeling)

    // PLAN v3 W2 (Builder B): background-free cutouts. ML Kit Subject Segmentation through Google Play services (beta;
    // the model is downloaded by Play services, manifest meta-data "subject_segment"), so the APK carries only the client.
    implementation(libs.play.services.mlkit.subject.segmentation)

    // M2 (Builder A): navigation host, ViewModels for the measure / history features.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
