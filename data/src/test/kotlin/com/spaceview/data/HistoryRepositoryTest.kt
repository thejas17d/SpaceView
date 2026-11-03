package com.spaceview.data

import com.spaceview.data.history.MeasurementDao
import com.spaceview.data.history.MeasurementEntity
import com.spaceview.data.history.MeasurementKind
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-memory DAO mirroring the SQL semantics the repository relies on. */
private class FakeDao : MeasurementDao {
    val rows = MutableStateFlow<List<MeasurementEntity>>(emptyList())
    private var nextId = 1L
    private fun sorted(l: List<MeasurementEntity>) = l.sortedWith(compareByDescending<MeasurementEntity> { it.timestampMillis }.thenByDescending { it.id })

    override suspend fun insert(entity: MeasurementEntity): Long {
        val id = nextId++
        rows.value = rows.value + entity.copy(id = id)
        return id
    }
    override fun observeRecent(limit: Int): Flow<List<MeasurementEntity>> = rows.map { sorted(it).take(limit) }
    override suspend fun get(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun setLabel(id: Long, label: String?) { rows.value = rows.value.map { if (it.id == id) it.copy(label = label) else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun count() = rows.value.size
    override suspend fun trimTo(keep: Int) { val k = sorted(rows.value).take(keep).map { it.id }.toSet(); rows.value = rows.value.filter { it.id in k } }
}

class HistoryRepositoryTest {

    @Test
    fun saveAndReadBackRectangle() = runTest {
        val dao = FakeDao()
        val repo = MeasurementRepository(dao)
        repo.save(
            MeasurementKind.RECTANGLE, Measurement(0.6, 0.004), Measurement(0.4, 0.003), Measurement(0.24, 0.003),
            Quality.FAIR, MeasureContext.FRAME, calibrated = true, timestampMillis = 1000L, label = "  Frame  ",
        )
        val saved = repo.observeHistory().first().single()
        assertEquals(MeasurementKind.RECTANGLE, saved.kind)
        assertEquals(0.6, saved.primary.value, 0.0)
        assertEquals(0.003, saved.secondary!!.sigma, 0.0)
        assertEquals(Quality.FAIR, saved.quality)
        assertEquals(MeasureContext.FRAME, saved.context)
        assertEquals("Frame", saved.label)
        assertEquals(true, saved.calibrated)
    }

    @Test
    fun newestFirstAndTrimmed() = runTest {
        val dao = FakeDao()
        val repo = MeasurementRepository(dao)
        for (i in 0 until MeasurementRepository.HISTORY_LIMIT + 5) {
            repo.save(MeasurementKind.DISTANCE, Measurement(i.toDouble(), 0.01), quality = Quality.GOOD, context = MeasureContext.OBJECT, calibrated = false, timestampMillis = i.toLong())
        }
        val list = repo.observeHistory().first()
        assertEquals(MeasurementRepository.HISTORY_LIMIT, dao.count())
        assertEquals((MeasurementRepository.HISTORY_LIMIT + 4).toDouble(), list.first().primary.value, 0.0)
    }

    @Test
    fun blankLabelBecomesNullAndCorruptRowsAreSkipped() = runTest {
        val dao = FakeDao()
        val repo = MeasurementRepository(dao)
        val id = repo.save(MeasurementKind.HEIGHT, Measurement(1.0, 0.01), quality = Quality.GOOD, context = MeasureContext.ROOM, calibrated = false, timestampMillis = 1L, label = "   ")
        assertNull(repo.observeHistory().first().single().label)
        repo.rename(id, "Door")
        assertEquals("Door", repo.observeHistory().first().single().label)
        dao.insert(MeasurementEntity(kind = "SOMETHING_NEW", valueMetres = 1.0, sigmaMetres = 0.0, quality = "GOOD", unitContext = "OBJECT", timestampMillis = 2L))
        assertEquals(1, repo.observeHistory().first().size)
        repo.delete(id)
        assertEquals(0, repo.observeHistory().first().size)
    }

    /** M2 review minor 9: a failing database (disk full, corrupt file) never crashes the UI calls. */
    @Test
    fun storageFailuresAreReportedNotThrown() = runTest {
        val broken = object : MeasurementDao by FakeDao() {
            override suspend fun insert(entity: MeasurementEntity): Long = throw java.io.IOException("full")
            override fun observeRecent(limit: Int): Flow<List<MeasurementEntity>> = kotlinx.coroutines.flow.flow { throw IllegalStateException("corrupt") }
            override suspend fun setLabel(id: Long, label: String?) = throw IllegalStateException("io")
            override suspend fun delete(id: Long) = throw IllegalStateException("io")
            override suspend fun deleteAll() = throw IllegalStateException("io")
        }
        val repo = MeasurementRepository(broken)
        assertEquals(emptyList<Any>(), repo.observeHistory().first())
        assertEquals(false, repo.rename(1, "x"))
        assertEquals(false, repo.delete(1))
        assertEquals(false, repo.clearAll())
        // save() throws so the caller can tell the user nothing was saved.
        try {
            repo.save(MeasurementKind.DISTANCE, Measurement(1.0, 0.01), quality = Quality.GOOD, context = MeasureContext.OBJECT, calibrated = false, timestampMillis = 1L)
            throw AssertionError("expected an exception")
        } catch (_: java.io.IOException) {}
    }
}
