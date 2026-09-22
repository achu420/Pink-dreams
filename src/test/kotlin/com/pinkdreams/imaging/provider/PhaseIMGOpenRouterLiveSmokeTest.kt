package com.pinkdreams.imaging.provider

import com.pinkdreams.imaging.provider.openrouter.OpenRouterImageProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Live OpenRouter image smoke. Skipped unless both env vars are set:
 * - OPENROUTER_API_KEY
 * - IMAGE_LIVE_SMOKE=true
 *
 * Never runs in default CI; opt-in only to avoid spend.
 */
class PhaseIMGOpenRouterLiveSmokeTest {

    @Test
    fun `live smoke submit returns completed candidates when enabled`() = runBlocking {
        val apiKey = System.getenv("OPENROUTER_API_KEY")
        val enabled = System.getenv("IMAGE_LIVE_SMOKE")?.equals("true", ignoreCase = true) == true
        assumeTrue(!apiKey.isNullOrBlank() && enabled, "Set OPENROUTER_API_KEY and IMAGE_LIVE_SMOKE=true to run")

        val provider = OpenRouterImageProvider(
            apiKey = apiKey!!,
            endpoint = System.getenv("OPENROUTER_IMAGE_ENDPOINT") ?: "https://openrouter.ai/api/v1/images",
            imageModel = System.getenv("OPENROUTER_IMAGE_MODEL") ?: "openai/gpt-image-2.5-flare",
        )

        val result = provider.submit(
            GenerationRequest(
                prompt = "A simple photorealistic portrait of a young woman, studio lighting, test smoke only",
                candidateCount = 1,
                widthPx = 512,
                heightPx = 512,
                idempotencyKey = "live-smoke-${UUID.randomUUID()}",
            )
        )

        assertTrue(
            result.status == GenerationStatus.COMPLETED ||
                result.status == GenerationStatus.QUEUED ||
                result.status == GenerationStatus.RUNNING,
            "unexpected status=${result.status} error=${result.error}",
        )
        if (result.status == GenerationStatus.COMPLETED) {
            assertEquals(true, result.candidates.isNotEmpty())
            assertTrue(result.candidates.first().imageData?.isNotEmpty() == true)
        }
    }
}
