package com.spaceview.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-006 / ADR-011 seams, enforced on the source tree:
 * - Play Billing types appear only in `billing/PlayBillingGateway.kt`;
 * - the Mobile Ads SDK and UMP appear only in `ads/` (AdsSdk.kt and BannerAd.kt);
 * - features never read billing directly: outside `pro/`, `billing/`, `settings/` and the container,
 *   no file imports `com.spaceview.app.billing` (they ask FeatureGate, contract K1).
 */
class BillingBoundaryTest {
    private val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
    private val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath

    private fun importers(prefix: String) = sources.filter { f -> f.readLines().any { it.startsWith("import $prefix") } }.map(::rel).toSet()

    @Test
    fun sourcesFound() = assertTrue(sources.size > 20)

    @Test
    fun playBillingOnlyInTheGateway() {
        assertEquals(setOf("com/spaceview/app/billing/PlayBillingGateway.kt"), importers("com.android.billingclient"))
    }

    @Test
    fun adsSdkOnlyBehindTheSeam() {
        val allowed = setOf("com/spaceview/app/ads/AdsSdk.kt", "com/spaceview/app/ads/BannerAd.kt")
        val users = importers("com.google.android.gms.ads") + importers("com.google.android.ump")
        assertTrue("unexpected: ${users - allowed}", (users - allowed).isEmpty())
    }

    @Test
    fun featuresDoNotUseBillingDirectly() {
        val allowedDirs = listOf("com/spaceview/app/billing/", "com/spaceview/app/pro/", "com/spaceview/app/settings/")
        val allowedFiles = setOf("com/spaceview/app/AppContainer.kt")
        val offenders = importers("com.spaceview.app.billing").filter { p -> p !in allowedFiles && allowedDirs.none { p.startsWith(it) } }
        assertTrue("use FeatureGate instead: $offenders", offenders.isEmpty())
    }
}
