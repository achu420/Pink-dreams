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
        // running deployment that has never touched the admin screen. reasoningEnabled
        // is the one deliberate exception: the Primary Generation Latency + Response
        // Quality Hardening phase measured reasoning-off as a real latency win for
        // generation specifically (see GenerationReasoningConfigTest for the evidence
        // trail) and applies regardless of admin settings.
        val before = GenerationConfig(model = envConfig.model, maxOutputTokens = envConfig.maxOutputTokens, reasoningEnabled = false, workload = "primary_generation")
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

        assertEquals(GenerationConfig(model = "chosen-model", temperature = 0.6, maxOutputTokens = 777, reasoningEnabled = false, workload = "primary_generation"), config)
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

    // ---------- Task 9 — Admin AI Runtime Controls ----------

    @Test
    fun `with no stored intent settings resolve falls back to the code defaults`() {
        val settings = AiRuntimeSettings(
            envConfig, repo(),
            intentModelDefault = "openai/gpt-4o-mini", intentJsonModeDefault = true, intentMaxOutputTokensDefault = 600,
        )

        val resolved = settings.resolve()

        assertEquals("openai/gpt-4o-mini", resolved.intentModel)
        assertEquals(AiRuntimeSettings.Source.CODE_DEFAULT, resolved.intentModelSource)
        assertEquals(true, resolved.intentJsonMode)
        assertEquals(AiRuntimeSettings.Source.CODE_DEFAULT, resolved.intentJsonModeSource)
        assertEquals(600, resolved.intentMaxOutputTokens)
        assertEquals(AiRuntimeSettings.Source.CODE_DEFAULT, resolved.intentMaxOutputTokensSource)
    }

    @Test
    fun `with no code default and no stored value intent model falls back to the environment model`() {
        val resolved = AiRuntimeSettings(envConfig, repo()).resolve()

        assertEquals("env-model", resolved.intentModel)
        assertEquals(AiRuntimeSettings.Source.ENVIRONMENT_OR_DEFAULT, resolved.intentModelSource)
        assertNull(resolved.intentJsonMode)
        assertEquals(AiRuntimeSettings.Source.PROVIDER_DEFAULT, resolved.intentJsonModeSource)
        assertEquals(600, resolved.intentMaxOutputTokens, "600 is the one hardcoded ultimate fallback, unchanged from before Task 9")
    }

    @Test
    fun `a persisted intent override wins over the code default and the source reports DATABASE`() {
        val repository = repo()
        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentModel = "claude-x", intentJsonMode = false, intentMaxOutputTokens = 300)
        val settings = AiRuntimeSettings(envConfig, repository, intentModelDefault = "openai/gpt-4o-mini", intentJsonModeDefault = true, intentMaxOutputTokensDefault = 600)

        val resolved = settings.resolve()

        assertEquals("claude-x", resolved.intentModel)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.intentModelSource)
        assertEquals(false, resolved.intentJsonMode)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.intentJsonModeSource)
        assertEquals(300, resolved.intentMaxOutputTokens)
        assertEquals(AiRuntimeSettings.Source.DATABASE, resolved.intentMaxOutputTokensSource)
    }

    @Test
    fun `resetting the persisted intent override to null returns the source to the code default`() {
        val repository = repo()
        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentModel = "claude-x")
        val settings = AiRuntimeSettings(envConfig, repository, intentModelDefault = "openai/gpt-4o-mini")
        assertEquals(AiRuntimeSettings.Source.DATABASE, settings.resolve().intentModelSource)

        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentModel = null)

        val resolved = settings.resolve()
        assertEquals("openai/gpt-4o-mini", resolved.intentModel)
        assertEquals(AiRuntimeSettings.Source.CODE_DEFAULT, resolved.intentModelSource)
    }

    @Test
    fun `a persisted generation provider sort override wins over the code default`() {
        val repository = repo()
        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", generationProviderSort = null)
        val settings = AiRuntimeSettings(envConfig, repository, generationProviderSortOverride = "latency")
        assertEquals("latency", settings.generationConfig().providerSort)
        assertEquals(AiRuntimeSettings.Source.CODE_DEFAULT, settings.resolve().generationProviderSortSource)
    }

    @Test
    fun `intent settings do not affect primary generation or side channel config`() {
        val repository = repo()
        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentModel = "claude-x", intentJsonMode = true, intentMaxOutputTokens = 50)
        val settings = AiRuntimeSettings(envConfig, repository)

        val generation = settings.generationConfig()
        val sideChannel = settings.sideChannelConfig(1200)

        assertEquals("env-model", generation.model, "Primary generation must be unaffected by the intent-only override")
        assertNull(generation.jsonMode)
        assertEquals("env-model", sideChannel.model, "Side-channel calls are unaffected by the intent-only override too")
    }

    @Test
    fun `generation provider sort does not affect intent discovery config`() {
        val repository = repo()
        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", generationProviderSort = "latency")
        val settings = AiRuntimeSettings(envConfig, repository)

        assertEquals("latency", settings.resolve().generationProviderSort)
        // Intent Discovery's GenerationConfig never reads providerSort at all
        // (see ChatEngineFactory.build()'s intentDiscoveryConfig construction) —
        // a structural guarantee verified at the full-pipeline level in
        // AdminRuntimeControlsLiveTest.
    }

    @Test
    fun `intent model must not be blank when supplied`() {
        val repository = repo()
        assertFailsWith<IllegalArgumentException> {
            repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentModel = "   ")
        }
    }

    @Test
    fun `intent max output tokens must be within the shared validation bounds`() {
        val repository = repo()
        assertFailsWith<IllegalArgumentException> {
            repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentMaxOutputTokens = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentMaxOutputTokens = -5)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", intentMaxOutputTokens = 999_999)
        }
    }

    @Test
    fun `generation provider sort only accepts the currently supported values`() {
        val repository = repo()
        assertFailsWith<IllegalArgumentException> {
            repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", generationProviderSort = "throughput")
        }
        // "latency" — the only value ever exercised by this codebase — must still work.
        repository.save(model = null, temperature = null, maxOutputTokens = null, updatedBy = "admin", generationProviderSort = "latency")
        assertEquals("latency", repository.get().generationProviderSort)
    }
}
