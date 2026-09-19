package com.pinkdreams.admin2

import com.pinkdreams.config.AiRuntimeSettings
import com.pinkdreams.config.LlmConfig
import com.pinkdreams.llm.GenerationConfig
import com.pinkdreams.persistence.database.DatabaseFactory
import com.pinkdreams.persistence.repositories.AiSettingsRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase ADMIN-2 sections 22-25, 31: AI runtime settings storage, validation,
 * and the DB → environment → default precedence.
 */
class AiRuntimeSettingsTest {

    private fun repo(): AiSettingsRepository =
        AiSettingsRepository(DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) })

    /** Stands in for "what the environment resolved", as LlmConfig.from() would produce. */
    private val envConfig = LlmConfig(apiKey = "k", model = "env-model", maxOutputTokens = 1024)

    // ---------- section 24: precedence ----------

    @Test
    fun `with no stored row every value comes from the environment or default`() {
        val resolved = AiRuntimeSettings(envConfig, repo()).resolve()

        assertEquals("env-model", resolved.model)
        assertEquals(1024, resolved.maxOutputTokens)
        assertNull(resolved.temperature, "Temperature was always unset before this phase and must stay unset")
        assertEquals(AiRuntimeSettings.Source.ENVIRONMENT_OR_DEFAULT, resolved.modelSource)
        assertEquals(AiRuntimeSettings.Source.ENVIRONMENT_OR_DEFAULT, resolved.maxOutputTokensSource)
    }

    @Test
    fun `an empty settings table reproduces the previous generation config exactly`() {
        // The regression this guards: adding an admin layer must not change a
        // running deployment that has never touched the admin screen.
        val before = GenerationConfig(model = envConfig.model, maxOutputTokens = envConfig.maxOutputTokens)
        val after = AiRuntimeSettings(envConfig, repo()).generationConfig()

        assertEquals(before, after)
    }

    @Test
    fun `a stored value overrides the environment`() {
        val repository = repo()
        repository.save(model = "db-model", temperature = 0.3, maxOutputTokens = 2048, updatedBy = "admin")

        val resolved = AiRuntimeSettings(envConfig, repository).resolve()

        assertEquals("db-model", resolved.model)
        assertEquals(0.3, resolved.temperature)
        assertEquals(2048, resolved.maxOutputTokens)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.modelSource)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.temperatureSource)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.maxOutputTokensSource)
    }

    @Test
    fun `settings fall back per field not all or nothing`() {
        val repository = repo()
        repository.save(model = null, temperature = 0.9, maxOutputTokens = null, updatedBy = "admin")

        val resolved = AiRuntimeSettings(envConfig, repository).resolve()

        assertEquals("env-model", resolved.model, "An unset model must still fall back")
        assertEquals(0.9, resolved.temperature)
        assertEquals(1024, resolved.maxOutputTokens, "An unset token budget must still fall back")
        assertEquals(AiRuntimeSettings.Source.ENVIRONMENT_OR_DEFAULT, resolved.modelSource)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.temperatureSource)
    }

    @Test
    fun `clearing a stored value hands the setting back to the environment`() {
        val repository = repo()
        repository.save("db-model", 0.5, 4096, "admin")
        repository.save(null, null, null, "admin")

        val resolved = AiRuntimeSettings(envConfig, repository).resolve()

        assertEquals("env-model", resolved.model)
        assertNull(resolved.temperature)
        assertEquals(1024, resolved.maxOutputTokens)
    }

    // ---------- section 31: persistence and validation ----------

    @Test
    fun `saving twice updates the single row rather than inserting another`() {
        val repository = repo()
        repository.save("a", 0.1, 100, "admin1")
        val second = repository.save("b", 0.2, 200, "admin2")

        assertEquals("b", repository.get().model)
        assertEquals("admin2", second.updatedBy)
        assertNotNull(second.updatedAt, "Auditability: every save records when and by whom")
    }

    @Test
    fun `temperature outside the permitted range is rejected`() {
        val repository = repo()
        assertFailsWith<IllegalArgumentException> { repository.save(null, -0.1, null, "admin") }
        assertFailsWith<IllegalArgumentException> { repository.save(null, 2.5, null, "admin") }
        // Boundaries are valid.
        repository.save(null, 0.0, null, "admin")
        repository.save(null, 2.0, null, "admin")
    }

    @Test
    fun `max output tokens outside the permitted range is rejected`() {
        val repository = repo()
        assertFailsWith<IllegalArgumentException> { repository.save(null, null, 0, "admin") }
        assertFailsWith<IllegalArgumentException> { repository.save(null, null, 99_999, "admin") }
    }

    @Test
    fun `a rejected save leaves the previous settings untouched`() {
        val repository = repo()
        repository.save("good-model", 0.4, 512, "admin")

        assertFailsWith<IllegalArgumentException> { repository.save("bad", 9.0, 512, "admin") }

        assertEquals("good-model", repository.get().model)
        assertEquals(0.4, repository.get().temperature)
    }

    // ---------- runtime resolution ----------

    @Test
    fun `generation config carries the resolved settings`() {
        val repository = repo()
        repository.save("chosen-model", 0.6, 777, "admin")

        val config = AiRuntimeSettings(envConfig, repository).generationConfig()

        assertEquals(GenerationConfig(model = "chosen-model", temperature = 0.6, maxOutputTokens = 777), config)
    }

    @Test
    fun `side channel calls follow the model but keep their own measured token budget`() {
        // Memory extraction, intent discovery and memory-engine maintenance fail
        // outright when their budget is too small for a reasoning model's
        // thinking tokens, so an admin lowering max tokens must not shrink them.
        val repository = repo()
        repository.save("chosen-model", 0.6, 50, "admin")

        val config = AiRuntimeSettings(envConfig, repository).sideChannelConfig(maxOutputTokens = 1200)

        assertEquals("chosen-model", config.model, "A model switch must apply everywhere")
        assertEquals(1200, config.maxOutputTokens, "The caller's measured budget must win")
        assertNull(config.temperature)
    }

    @Test
    fun `a settings lookup failure falls back to the environment instead of failing generation`() {
        val failing = object : AiSettingsRepository(
            DatabaseFactory.connectInMemory().also { DatabaseFactory.initializeSchema(it) },
        ) {
            override fun get(): StoredAiSettings = throw IllegalStateException("database unavailable")
        }

        val resolved = AiRuntimeSettings(envConfig, failing).resolve()

        assertEquals("env-model", resolved.model)
        assertEquals(1024, resolved.maxOutputTokens)
    }

    @Test
    fun `no repository at all resolves to the environment configuration`() {
        val resolved = AiRuntimeSettings(envConfig, repository = null).resolve()
        assertEquals("env-model", resolved.model)
        assertEquals(1024, resolved.maxOutputTokens)
    }
}
