package com.pinkdreams.imaging.provider.openrouter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [OpenRouterImageProvider.mapResolutionForModel].
 *
 * Seedream models only support "1K" and "2K" — they reject "512".
 * All other models use the standard 512 | 1K | 2K | 4K ladder.
 */
class OpenRouterResolutionMappingTest {

    // ── Seedream: null size ────────────────────────────────────────────────────

    @Test
    fun `seedream null size defaults to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", null))
    }

    // ── Seedream: ≤ 512 ───────────────────────────────────────────────────────

    @Test
    fun `seedream size 1 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 1))
    }

    @Test
    fun `seedream size 512 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 512))
    }

    // ── Seedream: 512 < size ≤ 1024 ───────────────────────────────────────────

    @Test
    fun `seedream size 513 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 513))
    }

    @Test
    fun `seedream size 1024 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 1024))
    }

    // ── Seedream: > 1024 ──────────────────────────────────────────────────────

    @Test
    fun `seedream size 1025 maps to 2K`() {
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 1025))
    }

    @Test
    fun `seedream size 2048 maps to 2K`() {
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 2048))
    }

    @Test
    fun `seedream size 4096 maps to 2K`() {
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/seedream-5-0-pro", 4096))
    }

    // ── Seedream: case insensitivity ───────────────────────────────────────────

    @Test
    fun `seedream model id with mixed case still maps correctly`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/Seedream-5-0-pro", 512))
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("bytedance-seed/SEEDREAM-5-0-pro", 2048))
    }

    // ── Non-seedream: null size ────────────────────────────────────────────────

    @Test
    fun `gpt-image null size maps to 512`() {
        assertEquals("512", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", null))
    }

    // ── Non-seedream: ≤ 768 → "512" ───────────────────────────────────────────

    @Test
    fun `gpt-image size 256 maps to 512`() {
        assertEquals("512", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 256))
    }

    @Test
    fun `gpt-image size 768 maps to 512`() {
        assertEquals("512", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 768))
    }

    // ── Non-seedream: 769–1536 → "1K" ─────────────────────────────────────────

    @Test
    fun `gpt-image size 769 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 769))
    }

    @Test
    fun `gpt-image size 1024 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 1024))
    }

    @Test
    fun `gpt-image size 1536 maps to 1K`() {
        assertEquals("1K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 1536))
    }

    // ── Non-seedream: 1537–3072 → "2K" ────────────────────────────────────────

    @Test
    fun `gpt-image size 1537 maps to 2K`() {
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 1537))
    }

    @Test
    fun `gpt-image size 2048 maps to 2K`() {
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 2048))
    }

    @Test
    fun `gpt-image size 3072 maps to 2K`() {
        assertEquals("2K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 3072))
    }

    // ── Non-seedream: > 3072 → "4K" ───────────────────────────────────────────

    @Test
    fun `gpt-image size 3073 maps to 4K`() {
        assertEquals("4K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 3073))
    }

    @Test
    fun `gpt-image size 4096 maps to 4K`() {
        assertEquals("4K", OpenRouterImageProvider.mapResolutionForModel("openai/gpt-image-2.5-flare", 4096))
    }

    // ── Arbitrary unknown model follows standard ladder ────────────────────────

    @Test
    fun `unknown model follows standard tier ladder`() {
        assertEquals("512", OpenRouterImageProvider.mapResolutionForModel("some/unknown-model", 512))
        assertEquals("1K",  OpenRouterImageProvider.mapResolutionForModel("some/unknown-model", 1024))
        assertEquals("2K",  OpenRouterImageProvider.mapResolutionForModel("some/unknown-model", 2048))
        assertEquals("4K",  OpenRouterImageProvider.mapResolutionForModel("some/unknown-model", 4096))
    }
}
