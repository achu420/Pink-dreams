package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.SkillContextRequirements
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class SkillContextRequirement(
    val skillKey: String,
    val requiredFields: List<String>?,
    val recommendedFields: List<String>?,
    val conditionalFields: List<String>?,
    val restrictedFields: List<String>?,
    val purpose: String?,
    val whenToUse: String?,
    val whenNotToUse: String?,
)

class SkillContextRequirementRepository(private val db: Database) {

    fun create(requirement: SkillContextRequirement): SkillContextRequirement = transaction(db) {
        SkillContextRequirements.insert {
            it[skillKey] = requirement.skillKey
            it[requiredFields] = requirement.requiredFields?.joinToString(",")
            it[recommendedFields] = requirement.recommendedFields?.joinToString(",")
            it[conditionalFields] = requirement.conditionalFields?.joinToString(",")
            it[restrictedFields] = requirement.restrictedFields?.joinToString(",")
            it[purpose] = requirement.purpose
            it[whenToUse] = requirement.whenToUse
            it[whenNotToUse] = requirement.whenNotToUse
        }
        requirement
    }

    fun findAll(): List<SkillContextRequirement> = transaction(db) {
        SkillContextRequirements.selectAll()
            .map(::rowToModel)
    }

    fun findBySkillKey(skillKey: String): SkillContextRequirement? = transaction(db) {
        SkillContextRequirements.select { SkillContextRequirements.skillKey eq skillKey }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun update(requirement: SkillContextRequirement): SkillContextRequirement = transaction(db) {
        SkillContextRequirements.update({ SkillContextRequirements.skillKey eq requirement.skillKey }) {
            it[requiredFields] = requirement.requiredFields?.joinToString(",")
            it[recommendedFields] = requirement.recommendedFields?.joinToString(",")
            it[conditionalFields] = requirement.conditionalFields?.joinToString(",")
            it[restrictedFields] = requirement.restrictedFields?.joinToString(",")
            it[purpose] = requirement.purpose
            it[whenToUse] = requirement.whenToUse
            it[whenNotToUse] = requirement.whenNotToUse
        }
        requirement
    }

    fun delete(skillKey: String): Boolean = transaction(db) {
        SkillContextRequirements.deleteWhere { SkillContextRequirements.skillKey eq skillKey } > 0
    }

    private fun rowToModel(row: ResultRow): SkillContextRequirement {
        val requiredStr = row[SkillContextRequirements.requiredFields]
        val recommendedStr = row[SkillContextRequirements.recommendedFields]
        val conditionalStr = row[SkillContextRequirements.conditionalFields]
        val restrictedStr = row[SkillContextRequirements.restrictedFields]
        return SkillContextRequirement(
            skillKey = row[SkillContextRequirements.skillKey],
            requiredFields = requiredStr?.split(",")?.filter { it.isNotEmpty() },
            recommendedFields = recommendedStr?.split(",")?.filter { it.isNotEmpty() },
            conditionalFields = conditionalStr?.split(",")?.filter { it.isNotEmpty() },
            restrictedFields = restrictedStr?.split(",")?.filter { it.isNotEmpty() },
            purpose = row[SkillContextRequirements.purpose],
            whenToUse = row[SkillContextRequirements.whenToUse],
            whenNotToUse = row[SkillContextRequirements.whenNotToUse],
        )
    }
}
