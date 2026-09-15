package com.pinkdreams.persistence.repositories

import com.pinkdreams.persistence.database.PersonaVisualVersions
import com.pinkdreams.persistence.database.PersonaVisualWardrobeItems
import com.pinkdreams.persistence.database.defaultNow
import com.pinkdreams.visual.identity.WardrobeItem
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class WardrobeRepository(private val db: Database) {

    fun addItem(
        versionId: UUID,
        category: String,
        subcategory: String,
        name: String,
        description: String? = null,
        color: String? = null,
        material: String? = null,
        fit: String? = null,
        pattern: String? = null,
        seasonTags: List<String> = emptyList(),
        styleTags: List<String> = emptyList(),
        accessories: List<String> = emptyList(),
    ): WardrobeItem = transaction(db) {
        val version = PersonaVisualVersionRepository(db).findById(versionId)
            ?: throw IllegalArgumentException("Visual version not found: $versionId")

        require(version.status == "draft") { "Wardrobe items can only be added to draft versions" }

        val item = WardrobeItem(
            id = UUID.randomUUID(),
            personaVisualVersionId = versionId,
            category = category,
            subcategory = subcategory,
            name = name,
            description = description,
            color = color,
            material = material,
            fit = fit,
            pattern = pattern,
            seasonTags = seasonTags,
            styleTags = styleTags,
            accessories = accessories,
            isAvailable = true
        )

        val validation = item.validate()
        require(validation.valid) { "Invalid wardrobe item: ${validation.errors.joinToString(", ")}" }

        PersonaVisualWardrobeItems.insert {
            it[PersonaVisualWardrobeItems.id] = item.id
            it[PersonaVisualWardrobeItems.personaVisualVersionId] = versionId
            it[PersonaVisualWardrobeItems.category] = category
            it[PersonaVisualWardrobeItems.subcategory] = subcategory
            it[PersonaVisualWardrobeItems.name] = name
            it[PersonaVisualWardrobeItems.description] = description
            it[PersonaVisualWardrobeItems.color] = color
            it[PersonaVisualWardrobeItems.material] = material
            it[PersonaVisualWardrobeItems.fit] = fit
            it[PersonaVisualWardrobeItems.pattern] = pattern
            it[PersonaVisualWardrobeItems.seasonTags] = serializeTags(seasonTags)
            it[PersonaVisualWardrobeItems.styleTags] = serializeTags(styleTags)
            it[PersonaVisualWardrobeItems.accessories] = serializeTags(accessories)
            it[PersonaVisualWardrobeItems.isAvailable] = true
            it[PersonaVisualWardrobeItems.createdAt] = defaultNow()
        }

        findItemById(item.id)!!
    }

    fun findItemById(itemId: UUID): WardrobeItem? = transaction(db) {
        PersonaVisualWardrobeItems.select { PersonaVisualWardrobeItems.id eq itemId }
            .map(::rowToModel)
            .singleOrNull()
    }

    fun findItemsForVersion(versionId: UUID): List<WardrobeItem> = transaction(db) {
        PersonaVisualWardrobeItems.select { PersonaVisualWardrobeItems.personaVisualVersionId eq versionId }
            .map(::rowToModel)
    }

    fun findItemsByCategory(versionId: UUID, category: String): List<WardrobeItem> = transaction(db) {
        PersonaVisualWardrobeItems.select {
            (PersonaVisualWardrobeItems.personaVisualVersionId eq versionId) and
            (PersonaVisualWardrobeItems.category eq category)
        }
            .map(::rowToModel)
    }

    fun updateItem(itemId: UUID, updates: WardrobeItem): WardrobeItem = transaction(db) {
        val item = findItemById(itemId) ?: throw IllegalArgumentException("Item not found: $itemId")

        val version = PersonaVisualVersionRepository(db).findById(item.personaVisualVersionId)
            ?: throw IllegalStateException("Visual version not found: ${item.personaVisualVersionId}")

        require(version.status == "draft") { "Wardrobe items can only be updated on draft versions" }

        val validation = updates.validate()
        require(validation.valid) { "Invalid wardrobe item: ${validation.errors.joinToString(", ")}" }

        PersonaVisualWardrobeItems.update({ PersonaVisualWardrobeItems.id eq itemId }) {
            it[PersonaVisualWardrobeItems.category] = updates.category
            it[PersonaVisualWardrobeItems.subcategory] = updates.subcategory
            it[PersonaVisualWardrobeItems.name] = updates.name
            it[PersonaVisualWardrobeItems.description] = updates.description
            it[PersonaVisualWardrobeItems.color] = updates.color
            it[PersonaVisualWardrobeItems.material] = updates.material
            it[PersonaVisualWardrobeItems.fit] = updates.fit
            it[PersonaVisualWardrobeItems.pattern] = updates.pattern
            it[PersonaVisualWardrobeItems.seasonTags] = serializeTags(updates.seasonTags)
            it[PersonaVisualWardrobeItems.styleTags] = serializeTags(updates.styleTags)
            it[PersonaVisualWardrobeItems.accessories] = serializeTags(updates.accessories)
            it[PersonaVisualWardrobeItems.isAvailable] = updates.isAvailable
        }

        findItemById(itemId)!!
    }

    fun removeItem(itemId: UUID) = transaction(db) {
        val item = findItemById(itemId) ?: throw IllegalArgumentException("Item not found: $itemId")

        val version = PersonaVisualVersionRepository(db).findById(item.personaVisualVersionId)
            ?: throw IllegalStateException("Visual version not found: ${item.personaVisualVersionId}")

        require(version.status == "draft") { "Wardrobe items can only be removed from draft versions" }

        PersonaVisualWardrobeItems.deleteWhere { PersonaVisualWardrobeItems.id eq itemId }
    }

    private fun rowToModel(row: ResultRow): WardrobeItem = WardrobeItem(
        id = row[PersonaVisualWardrobeItems.id],
        personaVisualVersionId = row[PersonaVisualWardrobeItems.personaVisualVersionId],
        category = row[PersonaVisualWardrobeItems.category],
        subcategory = row[PersonaVisualWardrobeItems.subcategory],
        name = row[PersonaVisualWardrobeItems.name],
        description = row[PersonaVisualWardrobeItems.description],
        color = row[PersonaVisualWardrobeItems.color],
        material = row[PersonaVisualWardrobeItems.material],
        fit = row[PersonaVisualWardrobeItems.fit],
        pattern = row[PersonaVisualWardrobeItems.pattern],
        seasonTags = deserializeTags(row[PersonaVisualWardrobeItems.seasonTags]),
        styleTags = deserializeTags(row[PersonaVisualWardrobeItems.styleTags]),
        accessories = deserializeTags(row[PersonaVisualWardrobeItems.accessories]),
        isAvailable = row[PersonaVisualWardrobeItems.isAvailable]
    )

    private fun serializeTags(tags: List<String>): String {
        return if (tags.isEmpty()) "[]" else tags.joinToString(",", "[\"", "\"]") { "\"$it\"" }
    }

    private fun deserializeTags(json: String): List<String> {
        if (json.isEmpty() || json == "[]" || json == "{}") return emptyList()
        return json.removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }
}
