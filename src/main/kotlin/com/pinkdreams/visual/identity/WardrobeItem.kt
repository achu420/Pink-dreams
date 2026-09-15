package com.pinkdreams.visual.identity

import java.util.UUID

data class WardrobeItem(
    val id: UUID,
    val personaVisualVersionId: UUID,
    val category: String,
    val subcategory: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val material: String? = null,
    val fit: String? = null,
    val pattern: String? = null,
    val seasonTags: List<String> = emptyList(),
    val styleTags: List<String> = emptyList(),
    val accessories: List<String> = emptyList(),
    val isAvailable: Boolean = true
) {
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()

        if (category.isBlank()) {
            errors.add("category is required")
        }

        if (subcategory.isBlank()) {
            errors.add("subcategory is required")
        }

        if (name.isBlank()) {
            errors.add("name is required")
        }

        val validCategories = setOf(
            "dress", "top", "bottom", "jacket", "shoe", "bag",
            "accessory", "jewelry", "belt", "scarf", "hat", "gloves"
        )

        if (!validCategories.contains(category.lowercase())) {
            errors.add("category must be one of: ${validCategories.joinToString(", ")}")
        }

        if (errors.isNotEmpty()) {
            return ValidationResult(valid = false, errors = errors)
        }

        return ValidationResult(valid = true, errors = emptyList())
    }
}
