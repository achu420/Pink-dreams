package com.pinkdreams.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SqlMigrationTest {
    @Test
    fun `initial migration contains required frozen schema tables`() {
        val migration = File("db/migration/V001__initial_schema.sql")

        assertTrue(migration.exists(), "Missing migration file: ${migration.path}")

        val sql = migration.readText()
        listOf(
            "CREATE TABLE conversation_engines",
            "CREATE TABLE persona_identity",
            "CREATE TABLE personas",
            "CREATE TABLE persona_core_versions",
            "CREATE TABLE user_profiles",
            "CREATE TABLE memory_facts",
            "CREATE TABLE entitlements",
            "CREATE TABLE conversations",
            "CREATE TABLE messages",
            "CREATE TABLE chat_request_executions",
            "CREATE UNIQUE INDEX one_active_engine",
            "CREATE UNIQUE INDEX uq_client_message_per_conversation",
            "fact_type",
            "criticality",
            "criticality_rank",
            "tier",
            "source",
            "evicted_at",
            "CREATE INDEX ix_memory_facts_user_persona",
            "CREATE INDEX ix_memory_facts_hot_selection",
        ).forEach { token ->
            assertTrue(sql.contains(token), "Migration missing required schema section: $token")
        }
    }
}
