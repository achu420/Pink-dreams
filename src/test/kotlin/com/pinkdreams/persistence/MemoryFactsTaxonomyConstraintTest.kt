package com.pinkdreams.persistence

import com.pinkdreams.chat.memory.MemoryService
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Schema-drift guard for the memory_facts CHECK constraints.
 *
 * The bug this exists to prevent: the taxonomy was extended in application code
 * (PERSONA fact types, the memory_engine source, the superseded/removed
 * statuses) while the database CHECK constraints kept the older, narrower
 * value sets. On real PostgreSQL every affected insert failed; the test suite
 * saw nothing, because tests run on H2 through
 * SchemaUtils.createMissingTablesAndColumns(), which creates no CHECK
 * constraints at all. No behavioral test on H2 can catch this class of bug —
 * so the guard is on the documented constraint text instead.
 *
 * This asserts agreement between the Kotlin taxonomy and the DDL that operators
 * apply to real databases. If someone adds a fact type or source in code, this
 * test fails until the migration that widens the constraint is written too.
 */
class MemoryFactsTaxonomyConstraintTest {

    private val constraintDdl: String by lazy {
        val migrations = File("db/migration").listFiles()
            ?.filter { it.isFile && it.extension == "sql" }
            ?.sortedBy { it.name }
            ?: emptyList()
        val schema = listOf(File("initiate Scheme.txt")).filter { it.isFile }
        (schema + migrations).joinToString("\n") { it.readText() }
    }

    /** The value list of the LAST definition of [constraintName] — later migrations supersede earlier ones. */
    private fun allowedValues(constraintName: String): Set<String> {
        val definitions = Regex(
            """ADD\s+CONSTRAINT\s+$constraintName\s+CHECK\s*\((.*?)\)\s*\)\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(constraintDdl).toList()

        // Also match the original inline CHECK (...) form used by the base schema.
        val inline = Regex(
            """CONSTRAINT\s+$constraintName\s+CHECK\s*\((.*?)\)\s*\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(constraintDdl).toList()

        val body = (definitions + inline).lastOrNull()?.groupValues?.get(1)
        assertTrue(body != null, "No DDL found defining $constraintName — cannot verify taxonomy agreement")
        return Regex("'([a-z_]+)'").findAll(body!!).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every fact type the application writes is permitted by the fact_type constraint`() {
        val allowed = allowedValues("memory_facts_fact_type_check")
        val missing = MemoryService.FACT_TYPES - allowed
        assertTrue(
            missing.isEmpty(),
            "These fact types are written by the application but rejected by memory_facts_fact_type_check: $missing. " +
                "Add a migration widening the constraint (see V006).",
        )
    }

    @Test
    fun `every source the application writes is permitted by the source constraint`() {
        val allowed = allowedValues("memory_facts_source_check")
        val missing = MemoryService.SOURCES - allowed
        assertTrue(
            missing.isEmpty(),
            "These sources are written by the application but rejected by memory_facts_source_check: $missing. " +
                "Add a migration widening the constraint (see V006).",
        )
    }

    @Test
    fun `the supersession statuses are permitted by the status constraint`() {
        val allowed = allowedValues("memory_facts_status_check")
        // MemoryService treats these as the inactive/dead statuses, and the Memory
        // Engine writes them when superseding or removing a memory.
        val required = setOf("open", "resolved", "superseded", "removed")
        val missing = required - allowed
        assertTrue(
            missing.isEmpty(),
            "These statuses are written by the application but rejected by memory_facts_status_check: $missing. " +
                "Add a migration widening the constraint (see V006).",
        )
    }
}
