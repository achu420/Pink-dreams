package com.pinkdreams.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Primary Generation Latency + Response Quality Hardening phase: locks in
 * that reasoning is disabled specifically for PRIMARY generation, while
 * remaining a provider-default (null, i.e. left on) everywhere else.
 *
 * Evidence: a live 35-turn accumulating conversation measured generation
 * averaging 11.2s (49% reasoning-driven, p95 ~31s). A paired reasoning
 * ON/OFF replay of 6 real captured generation prompts from that same
 * conversation (spanning casual chat, companionship, coaching, flirting, an
 * AI-nature question, and intimacy pacing) showed reasoning OFF cut average
 * latency roughly 3-4x with no observed loss of persona warmth or
 * naturalness — unlike Intent Discovery, where disabling reasoning
 * previously collapsed live routing from 92% to 24% (see
 * IntentDiscoverySamplingConfigTest). Generation is a conversational task,
 * not a discrete classification judgment, so the same tradeoff does not
 * apply. This test fails loudly if a future change silently re-enables
 * reasoning for generation or accidentally disables it elsewhere.
 */
class GenerationReasoningConfigTest {

    @Test
    fun `primary generation runs with reasoning disabled`() {
        val settings = AiRuntimeSettings(LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731"))

        val config = settings.generationConfig()

        assertEquals(false, config.reasoningEnabled, "Primary generation must run with reasoning disabled — measured latency win with no observed quality loss")
    }

    @Test
    fun `side-channel calls are unaffected by the generation reasoning change`() {
        val settings = AiRuntimeSettings(LlmConfig(apiKey = "k", model = "deepseek/deepseek-v4-flash-0731"))

        val sideChannel = settings.sideChannelConfig(1200)

        assertNull(sideChannel.reasoningEnabled, "Side-channel calls (memory extraction, continuity, memory-engine maintenance) must keep the provider default — this phase only touches primary generation")
    }
}
