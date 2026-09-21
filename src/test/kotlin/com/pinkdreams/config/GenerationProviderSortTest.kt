package com.pinkdreams.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Primary Generation Latency phase: locks in the reversible, test-only
 * OpenRouter provider-routing override for PRIMARY generation. Evidence: a
 * live 30-turn conversation showed generation latency ranging 368ms-50077ms
 * for structurally similar requests (no prompt/completion-size
 * correlation); a direct replay of the exact slow request reproduced the
 * same wide swing with no provider preference, while repeated
 * `sort=latency` calls stayed tight. Default (null) leaves production
 * behavior — and every existing test's expectations — unchanged.
 */
class GenerationProviderSortTest {

    @Test
    fun `with no override generation config has no provider sort preference`() {
        val settings = AiRuntimeSettings(LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731"))

        val config = settings.generationConfig()

        assertNull(config.providerSort)
    }

    @Test
    fun `an explicit override is reflected in the generation config`() {
        val settings = AiRuntimeSettings(
            LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731"),
            generationProviderSortOverride = "latency",
        )

        val config = settings.generationConfig()

        assertEquals("latency", config.providerSort)
    }

    @Test
    fun `side-channel calls are unaffected by the generation provider-sort override`() {
        val settings = AiRuntimeSettings(
            LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731"),
            generationProviderSortOverride = "latency",
        )

        val sideChannel = settings.sideChannelConfig(1200)

        assertNull(sideChannel.providerSort)
    }
}
