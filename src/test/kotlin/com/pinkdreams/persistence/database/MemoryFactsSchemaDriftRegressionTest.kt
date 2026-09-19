package com.pinkdreams.persistence.database

import org.jetbrains.exposed.sql.TextColumnType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Phase 4B.1 regression: the real production PostgreSQL `memory_facts` table
 * has `fact_type`, `criticality`, `tier`, `status`, and `source` declared as
 * TEXT — not VARCHAR(N). When the Exposed model here declared them as
 * `varchar(N)`, every server startup attempted an `ALTER COLUMN ... TYPE
 * VARCHAR(N)` to "fix" the mismatch. That ALTER always failed for
 * `criticality` specifically, because Postgres refuses to retype a column
 * used by a GENERATED column (`criticality_rank`, computed from
 * `criticality`) — and because the whole schema-sync call runs in one
 * transaction, that single failure silently rolled back every other pending
 * schema change in the same run, including (historically) the UserProfiles
 * gender/interest/city/age columns never actually landing in production,
 * which surfaced as an unrelated "Failed to create user" 500.
 *
 * This test does not connect to Postgres (H2 recreates the schema fresh every
 * run, so it can't reproduce the ALTER conflict itself) — it locks in the
 * Exposed column *type declarations* that prevent the mismatch from
 * recurring, so nobody "helpfully" reverts them to varchar(N) later.
 */
class MemoryFactsSchemaDriftRegressionTest {
    @Test
    fun `text columns matching production schema must remain declared as text not varchar`() {
        assertTrue(MemoryFacts.factType.columnType is TextColumnType, "fact_type must stay TEXT to match production")
        assertTrue(MemoryFacts.criticality.columnType is TextColumnType, "criticality must stay TEXT to match production")
        assertTrue(MemoryFacts.tier.columnType is TextColumnType, "tier must stay TEXT to match production")
        assertTrue(MemoryFacts.status.columnType is TextColumnType, "status must stay TEXT to match production")
        assertTrue(MemoryFacts.factSource.columnType is TextColumnType, "source must stay TEXT to match production")
    }
}
