// :engine — pure Kotlin/JVM measurement maths (no Android dependencies).
// Everything here is unit-tested on the JVM; :ar and :app consume it.
plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

dependencies { testImplementation(libs.junit) }
