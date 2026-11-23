package com.spaceview.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.spaceview.data.settings.MeasureSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SM1a: the hands-free toggle persists in the `spaceview_measure` store and is on by default. One write per file
 * (DataStore's rename fails on Windows once the file exists; see MeasureSettingsRepositoryTest).
 */
class HandsFreeSettingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(scope: CoroutineScope, name: String) = MeasureSettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { java.io.File(tmp.root, "$name.preferences_pb") },
    )

    @Test fun onByDefault() = runTest {
        assertTrue(repo(backgroundScope, "a").settings.first().handsFreeEnabled)
    }

    @Test fun switchingOffPersists() = runTest {
        val r = repo(backgroundScope, "b")
        r.setHandsFreeEnabled(false)
        assertFalse(r.settings.first().handsFreeEnabled)
    }
}
