package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.AdminAllowlist
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

/**
 * DB-backed admin allowlist. Additive to the ADMIN_USER_IDS environment
 * variable — never a replacement: [com.pinkdreams.auth.AdminAuthorizationProvider]
 * takes the UNION of the env-var set and this table, so an empty table changes
 * nothing for any existing deployment.
 */
open class AdminAllowlistRepository(private val db: Database) {
    data class AllowlistEntry(
        val userId: UUID,
        val note: String?,
        val addedBy: String?,
        val addedAt: LocalDateTime,
    )

    open fun list(): List<AllowlistEntry> = transaction(db) {
        AdminAllowlist.selectAll()
            .orderBy(AdminAllowlist.addedAt to SortOrder.ASC)
            .map(::rowToModel)
    }

    /** Just the ids — the hot path used on every admin request. */
    open fun listUserIds(): Set<UUID> = transaction(db) {
        AdminAllowlist.selectAll().map { it[AdminAllowlist.userId] }.toSet()
    }

    open fun contains(userId: UUID): Boolean = transaction(db) {
        AdminAllowlist.select { AdminAllowlist.userId eq userId }.empty().not()
    }

    /** Idempotent: adding an id that is already allowed is a no-op, not an error. */
    open fun add(userId: UUID, note: String? = null, addedBy: String? = null): AllowlistEntry = transaction(db) {
        if (!contains(userId)) {
            AdminAllowlist.insert {
                it[AdminAllowlist.userId] = userId
                it[AdminAllowlist.note] = note
                it[AdminAllowlist.addedBy] = addedBy
                it[AdminAllowlist.addedAt] = defaultNow()
            }
        }
        AdminAllowlist.select { AdminAllowlist.userId eq userId }.map(::rowToModel).single()
    }

    /** Returns true if a row was actually removed. */
    open fun remove(userId: UUID): Boolean = transaction(db) {
        AdminAllowlist.deleteWhere { AdminAllowlist.userId eq userId } > 0
    }

    open fun count(): Long = transaction(db) { AdminAllowlist.selectAll().count() }

    private fun rowToModel(row: ResultRow) = AllowlistEntry(
        userId = row[AdminAllowlist.userId],
        note = row[AdminAllowlist.note],
        addedBy = row[AdminAllowlist.addedBy],
        addedAt = row[AdminAllowlist.addedAt],
    )
}
