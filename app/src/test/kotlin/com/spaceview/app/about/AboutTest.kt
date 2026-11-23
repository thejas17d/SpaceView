package com.spaceview.app.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The About licence list is derived from, and must cover, the release dependency baseline. */
class OssLicencesTest {
    private val baseline: File =
        listOf(File("../ci/release-dependencies.txt"), File("ci/release-dependencies.txt")).first { it.isFile }

    private val groups: Set<String> = baseline.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringBefore(':') }
        .toSet()

    @Test
    fun `every Maven group in the release baseline is credited`() {
        assertTrue("baseline looks empty", groups.size > 20)
        val missing = groups.filter { OssLicences.entryFor(it) == null }
        assertEquals("Add these groups to OssLicences: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `no entry credits a library that is not shipped`() {
        val unused = OssLicences.all.flatMap { it.groups }.filter { prefix -> groups.none { it == prefix || it.startsWith("$prefix.") } }
        assertEquals("Remove these stale groups from OssLicences: $unused", emptyList<String>(), unused)
    }

    @Test
    fun `prefix matching is on whole name segments`() {
        assertEquals("AndroidX, Jetpack Compose, Room, DataStore, Navigation", OssLicences.entryFor("androidx.compose.ui")?.name)
        assertNull(OssLicences.entryFor("androidxfake"))
    }
}

class AppLinksTest {
    @Test
    fun `configured value wins, then the placeholder, blank means none`() {
        assertEquals("help@spaceview.app", AppLinks.resolve(" help@spaceview.app ", ""))
        assertEquals("p@x.io", AppLinks.resolve("", "p@x.io"))
        assertNull(AppLinks.resolve(" ", " "))
    }

    @Test
    fun `policy URL must be https without spaces or quotes (same rule as the build guard)`() {
        assertTrue(AppLinks.isValidPolicyUrl("https://spaceview.app/privacy"))
        assertFalse(AppLinks.isValidPolicyUrl("http://spaceview.app/privacy"))
        assertFalse(AppLinks.isValidPolicyUrl("https://"))
        assertFalse(AppLinks.isValidPolicyUrl("https://space view.app"))
        assertFalse(AppLinks.isValidPolicyUrl("https://x.app/\"p"))
    }

    @Test
    fun `email plausibility`() {
        assertTrue(AppLinks.isPlausibleEmail("help@spaceview.app"))
        assertFalse(AppLinks.isPlausibleEmail("help"))
        assertFalse(AppLinks.isPlausibleEmail("a@b@c.io"))
        assertFalse(AppLinks.isPlausibleEmail("he lp@x.io"))
    }

    @Test
    fun `Play listing link`() =
        assertEquals("https://play.google.com/store/apps/details?id=com.spaceview.app", AppLinks.playListing("com.spaceview.app"))
}
