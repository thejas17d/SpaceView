package com.spaceview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM check of the v2 → v3 migration against Room's exported schemas (R2 mitigation until the CI emulator job
 * runs `SpaceViewMigrationTest`): every column that 3.json has and 2.json lacks is added by exactly one
 * `MIGRATION_2_3` statement with the same type, NOT NULL flag and default, and nothing else changes (no column
 * removed, no table or index added). A typo or a NOT NULL column without a default fails here, not on a phone.
 */
class MigrationV3SchemaTest {
    private class Column(val table: String, val name: String, val affinity: String, val notNull: Boolean, val default: String?)

    private val dir = File("schemas/com.spaceview.data.SpaceViewDatabase")

    private fun columns(version: Int): Map<String, Column> {
        val f = File(dir, "$version.json")
        assertTrue("missing ${f.absolutePath} (Room exports it when :data compiles)", f.isFile)
        val text = f.readText()
        val out = LinkedHashMap<String, Column>()
        val tableRe = Regex("\"tableName\"\\s*:\\s*\"(\\w+)\"")
        val fieldRe = Regex(
            "\\{\\s*\"fieldPath\"\\s*:\\s*\"\\w+\"\\s*,\\s*\"columnName\"\\s*:\\s*\"(\\w+)\"\\s*,\\s*\"affinity\"\\s*:\\s*\"(\\w+)\"(?:\\s*,\\s*\"notNull\"\\s*:\\s*(true|false))?(?:\\s*,\\s*\"defaultValue\"\\s*:\\s*\"([^\"]*)\")?\\s*}",
        )
        val tables = tableRe.findAll(text).toList()
        for ((i, t) in tables.withIndex()) {
            val end = if (i + 1 < tables.size) tables[i + 1].range.first else text.length
            for (m in fieldRe.findAll(text.substring(t.range.last, end))) {
                val c = Column(t.groupValues[1], m.groupValues[1], m.groupValues[2], m.groupValues[3] == "true", m.groupValues[4].ifEmpty { null })
                out["${c.table}.${c.name}"] = c
            }
        }
        return out
    }

    @Test fun migration2To3AddsExactlyTheV3Columns() {
        val v2 = columns(2)
        val v3 = columns(3)
        assertTrue(v2.keys.all { it in v3 })
        assertTrue("schema parse found too few columns", v2.size > 20)
        val added = v3.filterKeys { it !in v2 }
        val stmt = Regex("ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)` (\\w+)( NOT NULL)?(?: DEFAULT (\\S+))?")
        val migrated = SpaceViewDatabase.V3_STATEMENTS.map { sql ->
            val m = stmt.matchEntire(sql) ?: throw AssertionError("Unexpected statement: $sql")
            Column(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].isNotEmpty(), m.groupValues[5].ifEmpty { null })
        }.associateBy { "${it.table}.${it.name}" }
        assertEquals(added.keys, migrated.keys)
        for ((k, c) in added) {
            val m = migrated.getValue(k)
            assertEquals(k, c.affinity, m.affinity)
            assertEquals(k, c.notNull, m.notNull)
            assertEquals(k, c.default, m.default)
            // ALTER TABLE cannot add a NOT NULL column without a default.
            if (c.notNull) assertTrue("$k is NOT NULL without a default", c.default != null)
        }
        assertEquals("'PROXY_BOX'", added.getValue("gallery_items.model_kind").default)
        assertEquals(3, SpaceViewDatabase.LATEST_VERSION)
        assertEquals(2, SpaceViewDatabase.MIGRATIONS.size)
    }

    @Test fun noTableOrIndexChangesBesidesColumns() {
        fun indexNames(v: Int) = Regex("\"name\"\\s*:\\s*\"(index_\\w+)\"").findAll(File(dir, "$v.json").readText()).map { it.groupValues[1] }.toSet()
        fun tables(v: Int) = Regex("\"tableName\"\\s*:\\s*\"(\\w+)\"").findAll(File(dir, "$v.json").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(tables(2), tables(3))
        assertEquals(indexNames(2), indexNames(3))
    }
}
