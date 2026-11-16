plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.spaceview.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // R2 (ARCHITECTURE-v2 §1.2, §9): SpaceViewMigrationTest also runs as a JVM unit test under Robolectric,
    // so `:data:testDebugUnitTest` (CI and locally) executes every migration against real SQLite with
    // the exported schemas. The androidTest sources are compiled into the unit tests too; AndroidJUnit4
    // delegates to Robolectric there. One source of truth, still runnable on a device.
    sourceSets {
        getByName("test") {
            kotlin.directories.add("src/androidTest/kotlin")
            assets.directories.add("$projectDir/schemas")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "ObsoleteLintCustomCheck")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Repositories expose engine types (Measurement, Quality, UnitPreference, ScaleCalibration).
    api(project(":engine"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric migration tests (see sourceSets above).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)

    // Room migration tests (MigrationTestHelper reads the exported schemas in data/schemas, which the
    // Room Gradle plugin adds to the androidTest assets). Device/emulator only; compiled on CI.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

// androidx.sqlite 2.6.2's SupportSQLiteDriver checks the requested file with substringAfterLast('/'), so
// MigrationTestHelper cannot open a database under Robolectric on a Windows host (backslash paths;
// IllegalArgumentException "This driver is configured to open a database named ..."). The migration test
// therefore runs on CI (ubuntu) and on devices, and is skipped only on Windows dev machines.
// shortcut: remove once androidx.sqlite compares platform paths.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        filter {
            excludeTestsMatching("com.spaceview.data.SpaceViewMigrationTest")
            isFailOnNoMatchingTests = false
        }
    }
}
