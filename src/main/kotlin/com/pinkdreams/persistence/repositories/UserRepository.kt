package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Read access to the `users` table, which is owned by the partner's auth system
 * in production. This repository never creates production user records; it only
 * checks existence so routes can distinguish "unknown user" from other failures.
 */
class UserRepository(private val db: Database) {
    fun exists(userId: UUID): Boolean = transaction(db) {
        !Users.select { Users.id eq userId }.limit(1).empty()
    }

    /** All known user IDs, unordered — callers needing display ordering (e.g. by
     * profile display name) should sort after joining with profile data. */
    fun findAll(): List<UUID> = transaction(db) {
        Users.selectAll().map { it[Users.id] }
    }

    /** Test/dev convenience — production user rows are created by the partner's auth system. */
    fun create(userId: UUID): Unit = transaction(db) {
        Users.insert {
            it[Users.id] = userId
        }
        Unit
    }
}
