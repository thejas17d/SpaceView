plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.spaceview.ar"
    compileSdk = 37

    defaultConfig {
        // ARCore (AR Required) and SceneView both need API 24+.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // SceneView 4.65 is compiled for JVM 21.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "ObsoleteLintCustomCheck")
    }
}

dependencies {
    implementation(project(":engine"))

    // Exposed as api: :app hosts the ARSceneView composable and talks to ARCore types (Frame, Pose, Anchor).
    api(libs.arcore)
    api(libs.sceneview.ar)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
