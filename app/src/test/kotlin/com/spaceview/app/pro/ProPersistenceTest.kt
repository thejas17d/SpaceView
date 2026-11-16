package com.spaceview.app.pro

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The entitlement cache on a real Preferences DataStore (one write per file: DataStore's JVM rename
 * fails on Windows for a second write, see DataStoreAdCounterStoreTest), and the backup exclusions (R4b).
 */
class ProPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun store(file: File) = CoroutineScope(Dispatchers.IO + SupervisorJob()).let { scope ->
        scopes += scope
        DataStoreEntitlementStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
            ) { file },
        )
    }

    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }

    @Test
    fun roundTripThroughFile() = runBlocking {
        val file = tmp.root.resolve("e.preferences_pb")
        val value = CachedEntitlement("pro", "abc123", 1_800_000_000_000L, autoRenewing = false, acknowledged = true, catalogVersion = 1)
        store(file).write(value)
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
        assertEquals(value, store(file).read())
    }

    @Test
    fun missingFileIsNoPro() = runBlocking {
        assertNull(store(tmp.root.resolve("none.preferences_pb")).read())
    }

    @Test
    fun corruptFileIsNoProNotACrash() = runBlocking {
        val file = tmp.root.resolve("bad.preferences_pb")
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55, 0x66))
        assertNull(store(file).read())
    }

    @Test
    fun entitlementFileIsExcludedFromBackupAndTransfer() {
        val res = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }
        val target = "datastore/$ENTITLEMENT_DATASTORE_NAME.preferences_pb"
        fun excludes(file: String, section: String?): Boolean {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, file))
            val roots = if (section == null) listOf(doc.documentElement) else List(doc.getElementsByTagName(section).length) { doc.getElementsByTagName(section).item(it) as org.w3c.dom.Element }
            assertTrue("$file has <$section>", roots.isNotEmpty())
            return roots.all { root ->
                val ex = root.getElementsByTagName("exclude")
                (0 until ex.length).any { (ex.item(it) as org.w3c.dom.Element).getAttribute("path") == target }
            }
        }
        assertTrue("backup_rules.xml", excludes("backup_rules.xml", null))
        assertTrue("cloud-backup", excludes("data_extraction_rules.xml", "cloud-backup"))
        assertTrue("device-transfer", excludes("data_extraction_rules.xml", "device-transfer"))
    }
}
