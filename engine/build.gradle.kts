// :engine — pure Kotlin/JVM measurement maths (no Android dependencies).
// Everything here is unit-tested on the JVM; :ar and :app consume it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

kotlin { jvmToolchain(21) }

dependencies { testImplementation(libs.junit) }

// M1 gate (PLAN §6): >= 90% line coverage of the engine. `koverVerify` fails the build below it;
// `koverHtmlReport` / `koverXmlReport` write the report under build/reports/kover.
kover {
    reports {
        verify {
            rule("Engine line coverage") {
                bound {
                    minValue = 90
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup = kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}
