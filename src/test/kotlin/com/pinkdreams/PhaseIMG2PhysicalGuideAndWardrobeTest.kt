package com.pinkdreams

import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.visual.identity.AgePresentation
import com.pinkdreams.visual.identity.Face
import com.pinkdreams.visual.identity.Eyes
import com.pinkdreams.visual.identity.Hair
import com.pinkdreams.visual.identity.Skin
import com.pinkdreams.visual.identity.Body
import com.pinkdreams.visual.identity.Anatomy
import com.pinkdreams.visual.identity.PhysicalGuide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PhaseIMG2PhysicalGuideAndWardrobeTest {

    private fun createPhysicalGuide(): PhysicalGuide = PhysicalGuide(
        agePresentation = AgePresentation(apparentAge = 25, adult = true),
        face = Face(faceShape = "oval", jawline = "defined"),
        eyes = Eyes(color = "brown", shape = "almond"),
        hair = Hair(color = "black", length = "long", texture = "straight", style = "layered"),
        skin = Skin(tone = "warm", undertone = "golden"),
        body = Body(height = "5'6\"", build = "athletic"),
        anatomy = Anatomy(shoulders = "narrow", waist = "defined"),
        distinctiveFeatures = listOf("freckles", "beauty mark"),
        notes = "Elegant and refined aesthetic"
    )

    // ========== PHYSICAL GUIDE TESTS ==========

    @Test
    fun `IMG2 - Create physical guide for draft visual version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val guide = createPhysicalGuide()

        val created = guideRepo.setPhysicalGuide(draft.id, guide)

        assertNotNull(created, "Physical guide should be created")
        assertEquals(25, created.agePresentation.apparentAge, "Age should match")
        assertEquals("brown", created.eyes.color, "Eye color should match")
    }

    @Test
    fun `IMG2 - Retrieve physical guide`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val guide = createPhysicalGuide()

        guideRepo.setPhysicalGuide(draft.id, guide)
        val retrieved = guideRepo.getPhysicalGuide(draft.id)

        assertNotNull(retrieved, "Physical guide should be retrievable")
        assertEquals(guide.agePresentation.apparentAge, retrieved.agePresentation.apparentAge)
        assertEquals(guide.eyes.color, retrieved.eyes.color)
    }

    @Test
    fun `IMG2 - Cannot set physical guide on published version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val guide = createPhysicalGuide()

        guideRepo.setPhysicalGuide(draft.id, guide)
        val published = versionRepo.publishVisualVersion(draft.id)

        try {
            guideRepo.setPhysicalGuide(published.id, guide.copy(agePresentation = AgePresentation(apparentAge = 30)))
            fail("Should not allow updating physical guide on published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `IMG2 - Adult invariant enforced`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val invalidGuide = createPhysicalGuide().copy(
            agePresentation = AgePresentation(apparentAge = 25, adult = false)
        )

        try {
            guideRepo.setPhysicalGuide(draft.id, invalidGuide)
            fail("Should not allow adult=false")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("adult") ?: false)
        }
    }

    @Test
    fun `IMG2 - Age presentation validation`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val invalidGuide = createPhysicalGuide().copy(
            agePresentation = AgePresentation(apparentAge = 15, adult = true)
        )

        try {
            guideRepo.setPhysicalGuide(draft.id, invalidGuide)
            fail("Should not allow age < 18")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("18") ?: false)
        }
    }

    @Test
    fun `IMG2 - Update adult flag on draft version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val guide = createPhysicalGuide()

        guideRepo.setPhysicalGuide(draft.id, guide)
        val updated = guideRepo.updatePhysicalGuideAdultFlag(draft.id, true)

        assertTrue(updated.agePresentation.adult, "Adult flag should be true")
    }

    // ========== WARDROBE TESTS ==========

    @Test
    fun `IMG2 - Add wardrobe item to draft version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val item = wardrobeRepo.addItem(
            versionId = draft.id,
            category = "dress",
            subcategory = "evening",
            name = "Black evening gown",
            color = "black",
            material = "silk",
            fit = "fitted",
            seasonTags = listOf("winter", "formal")
        )

        assertNotNull(item.id, "Item should have an ID")
        assertEquals("dress", item.category)
        assertEquals("Black evening gown", item.name)
    }

    @Test
    fun `IMG2 - List wardrobe items for version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        wardrobeRepo.addItem(draft.id, "dress", "casual", "Blue sundress", color = "blue")
        wardrobeRepo.addItem(draft.id, "top", "casual", "White t-shirt", color = "white")
        wardrobeRepo.addItem(draft.id, "bottom", "jeans", "Dark jeans", color = "indigo")

        val items = wardrobeRepo.findItemsForVersion(draft.id)
        assertEquals(3, items.size, "Should retrieve all 3 items")
    }

    @Test
    fun `IMG2 - Find wardrobe items by category`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        wardrobeRepo.addItem(draft.id, "dress", "casual", "Dress 1")
        wardrobeRepo.addItem(draft.id, "dress", "formal", "Dress 2")
        wardrobeRepo.addItem(draft.id, "top", "casual", "Top 1")

        val dresses = wardrobeRepo.findItemsByCategory(draft.id, "dress")
        assertEquals(2, dresses.size, "Should find 2 dresses")
        assertTrue(dresses.all { it.category == "dress" })
    }

    @Test
    fun `IMG2 - Update wardrobe item on draft version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val item = wardrobeRepo.addItem(
            versionId = draft.id,
            category = "dress",
            subcategory = "casual",
            name = "Blue dress",
            color = "blue"
        )

        val updated = wardrobeRepo.updateItem(
            item.id,
            item.copy(name = "Light blue dress", color = "light blue")
        )

        assertEquals("Light blue dress", updated.name)
        assertEquals("light blue", updated.color)
    }

    @Test
    fun `IMG2 - Remove wardrobe item from draft version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val item = wardrobeRepo.addItem(draft.id, "dress", "casual", "Dress")
        wardrobeRepo.removeItem(item.id)

        val retrieved = wardrobeRepo.findItemById(item.id)
        assertNull(retrieved, "Item should be deleted")
    }

    @Test
    fun `IMG2 - Cannot modify wardrobe items on published version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val item = wardrobeRepo.addItem(draft.id, "dress", "casual", "Dress")
        val published = versionRepo.publishVisualVersion(draft.id)

        try {
            wardrobeRepo.addItem(published.id, "top", "casual", "New top")
            fail("Should not allow adding items to published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `IMG2 - Cannot remove items from published version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        val item = wardrobeRepo.addItem(draft.id, "dress", "casual", "Dress")
        val published = versionRepo.publishVisualVersion(draft.id)

        try {
            wardrobeRepo.removeItem(item.id)
            fail("Should not allow removing items from published version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `IMG2 - Wardrobe category validation`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        try {
            wardrobeRepo.addItem(draft.id, "invalid_category", "type", "Item")
            fail("Should reject invalid category")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("category") ?: false)
        }
    }

    @Test
    fun `IMG2 - Multiple visual versions can have different wardrobes`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()

        val v1 = versionRepo.create(personaIdentityId = identity.id, version = 1)
        wardrobeRepo.addItem(v1.id, "dress", "casual", "V1 Dress")
        val v1Published = versionRepo.publishVisualVersion(v1.id)

        val v2 = versionRepo.create(personaIdentityId = identity.id, version = 2)
        wardrobeRepo.addItem(v2.id, "dress", "formal", "V2 Formal Dress")
        val v2Published = versionRepo.publishVisualVersion(v2.id)

        val v1Items = wardrobeRepo.findItemsForVersion(v1Published.id)
        val v2Items = wardrobeRepo.findItemsForVersion(v2Published.id)

        assertEquals(1, v1Items.size)
        assertEquals(1, v2Items.size)
        assertEquals("V1 Dress", v1Items[0].name)
        assertEquals("V2 Formal Dress", v2Items[0].name)
    }

    @Test
    fun `IMG2 - New version starts with empty wardrobe`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()

        val v1 = versionRepo.create(personaIdentityId = identity.id, version = 1)
        wardrobeRepo.addItem(v1.id, "dress", "casual", "Dress")
        versionRepo.publishVisualVersion(v1.id)

        val v2 = versionRepo.create(personaIdentityId = identity.id, version = 2)
        val v2Items = wardrobeRepo.findItemsForVersion(v2.id)

        assertEquals(0, v2Items.size, "New version should have empty wardrobe")
    }

    @Test
    fun `IMG2 - Cannot modify physical guide on archived version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val guideRepo = PersonalGuideRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)
        val guide = createPhysicalGuide()

        guideRepo.setPhysicalGuide(draft.id, guide)
        val published = versionRepo.publishVisualVersion(draft.id)
        val archived = versionRepo.archiveVisualVersion(published.id)

        try {
            guideRepo.setPhysicalGuide(archived.id, guide.copy(agePresentation = AgePresentation(apparentAge = 30)))
            fail("Should not allow updating physical guide on archived version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }

    @Test
    fun `IMG2 - Cannot modify wardrobe on archived version`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)

        val identityRepo = PersonaIdentityRepository(db)
        val versionRepo = PersonaVisualVersionRepository(db)
        val wardrobeRepo = WardrobeRepository(db)

        val identity = identityRepo.create()
        val draft = versionRepo.create(personaIdentityId = identity.id, version = 1)

        wardrobeRepo.addItem(draft.id, "dress", "casual", "Dress")
        val published = versionRepo.publishVisualVersion(draft.id)
        val archived = versionRepo.archiveVisualVersion(published.id)

        try {
            wardrobeRepo.addItem(archived.id, "top", "casual", "Top")
            fail("Should not allow adding items to archived version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("draft") ?: false)
        }
    }
}
