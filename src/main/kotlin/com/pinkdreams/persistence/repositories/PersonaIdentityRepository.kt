package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaIdentity
import com.pinkdreams.persistence.database.defaultNow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class PersonaIdentityRepository(private val db: Database) {
    data class Identity(
        val id: UUID,
        val activeVisualVersionId: UUID?,
    )

    fun create(): Identity = transaction(db) {
        val id = UUID.randomUUID()
        PersonaIdentity.insert {
            it[PersonaIdentity.id] = id
            it[PersonaIdentity.createdAt] = defaultNow()
        }
        findById(id)!!
    }

    fun findById(id: UUID): Identity? = transaction(db) {
        PersonaIdentity.select { PersonaIdentity.id eq id }
            .map(::rowToModel)
            .singleOrNull()
    }

    private fun rowToModel(row: ResultRow): Identity = Identity(
        id = row[PersonaIdentity.id],
        activeVisualVersionId = row[PersonaIdentity.activeVisualVersionId],
    )
}
