package com.pinkdreams.persistence

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Schema-drift guard for the new `conversations.execution_mode` CHECK
 * constraint (Phase ADMIN-3), mirroring MemoryFactsTaxonomyConstraintTest:
 * H2/SchemaUtils creates no CHECK constraints, so only a documented-DDL check
 * like this one can catch a real-PostgreSQL mismatch before it reaches
 * production. See V006/V008 migration notes for the class of bug this guards.
 */
class ConversationsExecutionModeConstraintTest {

    @Test
    fun `V008 defines an execution_mode constraint permitting exactly PRODUCTION and TEST`() {
        val migration = File("db/migration/V008__test_chat_configuration_snapshot.sql")
        assertTrue(migration.exists(), "V008 migration must exist")
        val text = migration.readText()

        assertTrue(text.contains("conversations_execution_mode_check"), "V008 must define the execution_mode constraint")
        assertTrue(text.contains("'PRODUCTION'") && text.contains("'TEST'"), "The constraint must permit both PRODUCTION and TEST")
        // Guards against a future third mode being added to the Kotlin enum/logic
        // without widening this constraint to match.
        val allowedValues = Regex("""CHECK\s*\(execution_mode IN \(([^)]*)\)\)""")
            .find(text)?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('\'') }
            ?.toSet()
        assertTrue(allowedValues == setOf("PRODUCTION", "TEST"), "Unexpected constraint value set: $allowedValues")
    }
}
