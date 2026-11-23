package com.spaceview.app.about

import androidx.annotation.StringRes
import com.spaceview.app.R

/**
 * The open-source licence list shown in About (M5), derived from the release dependency baseline
 * `ci/release-dependencies.txt` (the same file CI's dependency guard checks). Every Maven group in that baseline must
 * be covered by one entry's [groups]: `OssLicencesTest` fails otherwise, so a new dependency cannot ship without
 * being credited here. Regenerate the baseline first (`ci/dependency_guard.py update`), then update this list.
 *
 * @property name what the user sees (library names, not Maven coordinates).
 * @property licence the licence's display name.
 * @property groups Maven group prefixes this entry covers (a prefix matches the group itself or `<prefix>.…`).
 */
data class OssLicence(val name: String, @StringRes val licence: Int, val groups: List<String>)

object OssLicences {
    val all: List<OssLicence> = listOf(
        OssLicence("AndroidX, Jetpack Compose, Room, DataStore, Navigation", R.string.about_licence_apache, listOf("androidx")),
        OssLicence("Kotlin, kotlinx.coroutines, kotlinx.serialization, JetBrains annotations", R.string.about_licence_apache, listOf("org.jetbrains")),
        OssLicence("SceneView / ARSceneView", R.string.about_licence_apache, listOf("io.github.sceneview")),
        OssLicence("Filament (Google)", R.string.about_licence_apache, listOf("com.google.android.filament")),
        OssLicence("kotlin-math (Romain Guy)", R.string.about_licence_apache, listOf("dev.romainguy")),
        OssLicence("Okio (Square)", R.string.about_licence_apache, listOf("com.squareup.okio")),
        OssLicence("Fuel and Result (Kittinunf)", R.string.about_licence_mit, listOf("com.github.kittinunf")),
        OssLicence(
            "Guava, Error Prone annotations, J2ObjC annotations, JSR 305",
            R.string.about_licence_apache,
            listOf("com.google.guava", "com.google.errorprone", "com.google.j2objc", "com.google.code.findbugs"),
        ),
        OssLicence("Checker Framework qualifiers", R.string.about_licence_mit, listOf("org.checkerframework")),
        OssLicence("javax.inject", R.string.about_licence_apache, listOf("javax.inject")),
        OssLicence("JSpecify", R.string.about_licence_apache, listOf("org.jspecify")),
        OssLicence("Firebase components, Google Data Transport", R.string.about_licence_apache, listOf("com.google.firebase", "com.google.android.datatransport")),
        OssLicence("ARCore SDK for Android (Google)", R.string.about_licence_google_apis, listOf("com.google.ar")),
        OssLicence(
            "Google Play services, Google Mobile Ads SDK, User Messaging Platform",
            R.string.about_licence_android_sdk,
            listOf("com.google.android.gms", "com.google.android.ump"),
        ),
        OssLicence("Google Play Billing Library", R.string.about_licence_android_sdk, listOf("com.android.billingclient")),
        OssLicence("Google Play In-App Review, Play Core", R.string.about_licence_play_core, listOf("com.google.android.play")),
        OssLicence("ML Kit Image Labeling, ML Kit Vision (Google)", R.string.about_licence_mlkit, listOf("com.google.mlkit", "com.google.android.odml")),
    )

    /** The entry covering Maven [group], or null. */
    fun entryFor(group: String): OssLicence? = all.firstOrNull { e -> e.groups.any { group == it || group.startsWith("$it.") } }
}
