package com.pinkdreams.imaging.benchmark

import com.pinkdreams.imaging.orchestration.GeneratedCandidateRepository
import com.pinkdreams.imaging.orchestration.ImageGenerationOrchestrator
import com.pinkdreams.imaging.orchestration.ImageGenerationService
import com.pinkdreams.imaging.provider.openrouter.OpenRouterImageModelCatalog
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.ImageJobRepository
import com.pinkdreams.persistence.repositories.PersonaIdentityRepository
import com.pinkdreams.persistence.repositories.PersonaRepository
import com.pinkdreams.persistence.repositories.PersonaVisualVersionRepository
import com.pinkdreams.persistence.repositories.PersonalGuideRepository
import com.pinkdreams.persistence.repositories.ReferenceImageRepository
import com.pinkdreams.persistence.repositories.WardrobeRepository
import com.pinkdreams.storage.InMemoryObjectStorage
import com.pinkdreams.visual.identity.PersonaVisualAdminService
import com.pinkdreams.visual.identity.ReferenceImage
import com.pinkdreams.visual.identity.ReferenceRole
import com.pinkdreams.visual.identity.ReferenceSource
import com.pinkdreams.visual.identity.ReferenceStatus
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageModelBenchmarkPreparationTest {

    private val catalogJson = """
        {"data":[
          {"id":"bytedance-seed/seedream-5-0-pro","name":"ByteDance Seed: Seedream 5.0 Pro",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image"]},
           "supported_parameters":{"resolution":{"type":"enum","values":["1K","2K"]},"n":{"type":"range","min":1,"max":1},"input_references":{"type":"range","min":0,"max":14}}},
          {"id":"bytedance-seed/seedream-5-0-lite","name":"ByteDance Seed: Seedream 5.0 Lite",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image"]},
           "supported_parameters":{"resolution":{"type":"enum","values":["2K","4K"]},"n":{"type":"range","min":1,"max":4},"input_references":{"type":"range","min":0,"max":14}}},
          {"id":"openai/gpt-image-2","name":"OpenAI: GPT Image 2",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image"]},
           "supported_parameters":{"n":{"type":"range","min":1,"max":10},"input_references":{"type":"range","min":0,"max":16}}},
          {"id":"openai/gpt-image-2.5-flare","name":"OpenAI: GPT Image 2.5 Flare",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image"]},
           "supported_parameters":{"n":{"type":"range","min":1,"max":10},"input_references":{"type":"range","min":0,"max":16}}},
          {"id":"qwen/qwen-image-3-pro","name":"Qwen: Qwen Image 3 Pro",
           "architecture":{"input_modalities":["text","image"],"output_modalities":["image"]},
           "supported_parameters":{"resolution":{"type":"enum","values":["1K","2K"]},"n":{"type":"range","min":1,"max":6},"input_references":{"type":"range","min":0,"max":4}}}
        ]}
    """.trimIndent()

    @Test
    fun `catalog parses live capability fields`() {
        val models = OpenRouterImageModelCatalog("unused").parseCatalogBody(catalogJson)
        val lite = models.first { it.modelId.contains("lite") }
        assertEquals(4, lite.maxCandidateCount)
        assertEquals(14, lite.maxReferenceImages)
        assertEquals(listOf("2K", "4K"), lite.supportedResolutions)
    }

    @Test
    fun `slots do not substitute gpt image 2 with 2_5 flare`() {
        val catalog = OpenRouterImageModelCatalog("unused").parseCatalogBody(catalogJson)
        val gpt2 = BenchmarkModelSlots.match(BenchmarkModelSlots.ALL.first { it.slotKey == "GPT_IMAGE_2" }, catalog)
        assertEquals("openai/gpt-image-2", gpt2?.modelId)
        assertEquals(null, BenchmarkModelSlots.match(BenchmarkModelSlots.ALL.first { it.slotKey == "MUSE_IMAGE" }, catalog))
    }

    @Test
    fun `reference policy omits from the end never randomly`() {
        val versionId = UUID.randomUUID()
        val fake = ReferenceRole.STANDARD_SLOTS.mapIndexed { i, role ->
            ReferenceImage(
                id = UUID.randomUUID(),
                personaVisualVersionId = versionId,
                storageKey = "k$i",
                contentType = "image/png",
                fileSize = 1,
                role = role,
                status = ReferenceStatus.UPLOADED,
                source = ReferenceSource.HUMAN_UPLOADED,
            )
        }
        val sel = BenchmarkReferencePolicy.select(fake, 4)
        assertEquals(listOf(ReferenceRole.BACK), sel.omitted.map { it.role })
        assertEquals("MODEL_REFERENCE_LIMIT_4", sel.reason)
    }

    @Test
    fun `resolution policy records 2K deviation when 1K missing`() {
        val choice = BenchmarkResolutionPolicy.choose(listOf("2K", "4K"))
        assertEquals("1K", choice.requestedResolution)
        assertEquals("2K", choice.actualResolution)
        assertEquals("COMMON_1K_UNSUPPORTED_USED_2K", choice.deviation)
    }

    @Test
    fun `create run persists prompts models executions without starting jobs`() {
        val db = DatabaseFactory.connectInMemory()
        DatabaseFactory.initializeSchema(db)
        val storage = InMemoryObjectStorage()
        val personaRepo = PersonaRepository(db)
        val visualAdmin = PersonaVisualAdminService(
            personaRepository = personaRepo,
            personaIdentityRepository = PersonaIdentityRepository(db),
            visualVersionRepository = PersonaVisualVersionRepository(db),
            personalGuideRepository = PersonalGuideRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
        )
        val persona = personaRepo.create(
            slug = "bench-${UUID.randomUUID().toString().take(8)}",
            displayName = "Bench Persona",
            gender = "female",
            orientation = "bisexual",
            apparentAge = 25,
            languageProfile = emptyMap(),
        )
        visualAdmin.ensureDraft(persona.id, author = "test")
        ReferenceRole.STANDARD_SLOTS.forEach { role ->
            visualAdmin.uploadReference(persona.id, role, ByteArray(32) { 1 }, "image/png")
        }
        visualAdmin.publishAndActivateDraft(persona.id)

        val catalog = OpenRouterImageModelCatalog("unused").parseCatalogBody(catalogJson)
        val jobRepo = ImageJobRepository(db)
        val generation = ImageGenerationService(
            personaRepository = personaRepo,
            visualVersionRepository = PersonaVisualVersionRepository(db),
            wardrobeRepository = WardrobeRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
            orchestrator = ImageGenerationOrchestrator(
                compiler = com.pinkdreams.imaging.compiler.PromptCompiler(),
                jobRepository = jobRepo,
            ),
            jobRepository = jobRepo,
            candidateRepository = GeneratedCandidateRepository(db),
        )
        val service = ImageModelBenchmarkService(
            repository = BenchmarkRepository(db),
            imageGenerationService = generation,
            personaRepository = personaRepo,
            visualVersionRepository = PersonaVisualVersionRepository(db),
            referenceImageRepository = ReferenceImageRepository(db, storage),
            personalGuideRepository = PersonalGuideRepository(db),
            jobRepository = jobRepo,
            candidateRepository = GeneratedCandidateRepository(db),
            objectStorage = storage,
            catalogFactory = { FakeCatalog(catalog) },
            apiKeyProvider = { "test-key" },
        )

        assertTrue(service.assessPersona(persona.id).eligible)

        val runId = service.create(
            ImageModelBenchmarkService.CreateRunCommand(
                name = "prep",
                personaIds = listOf(persona.id),
                slotKeys = listOf("SEEDREAM_5_PRO", "SEEDREAM_5_LITE", "GPT_IMAGE_2", "QWEN_IMAGE_3_PRO", "MUSE_IMAGE"),
                promptIds = BenchmarkPrompts.ALL.map { it.promptId },
                start = false,
            )
        )
        val detail = service.getDetail(runId)!!
        assertEquals(4, detail.prompts.size)
        assertEquals("READY", detail.run.status)
        assertTrue(detail.executions.none { it.jobId != null })
        val qwen = detail.executions.first { it.slotKey == "QWEN_IMAGE_3_PRO" }
        assertTrue(qwen.referencesOmitted!!.contains("BACK"))
        val lite = detail.executions.first { it.slotKey == "SEEDREAM_5_LITE" }
        assertEquals(4, lite.requestedCandidates)
        assertEquals("2K", lite.actualResolution)
        assertEquals("MODEL_UNAVAILABLE", detail.executions.first { it.slotKey == "MUSE_IMAGE" }.status)
        val eval = service.evaluate(
            executionId = qwen.id,
            candidateId = null,
            identityRating = 3,
            identityRemarks = "face ok",
            realismRating = 2,
            realismRemarks = "hands weak",
            adminDecision = null,
            adminRemarks = "prep only",
            evaluatedBy = "admin",
        )
        assertEquals(3, eval.identityRating)
        assertEquals(2, eval.realismRating)
        assertEquals(0, jobRepo.findByStatus(com.pinkdreams.imaging.job.ImageJobStatus.QUEUED, 50).size)
    }

    private class FakeCatalog(
        private val models: List<OpenRouterImageModelCatalog.DiscoveredModel>,
    ) : OpenRouterImageModelCatalog("fake") {
        override fun fetch(): List<DiscoveredModel> = models
    }
}
