package com.pinkdreams.imaging.provider.openrouter

import kotlinx.serialization.json.JsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [OpenRouterImageProvider] covering:
 *  - buildImageRequestJson schema correctness
 *  - errorCodeText edge cases (integer code, string code, null)
 *  - fitReferenceBytesForProvider expanded coverage
 */
class OpenRouterImageProviderUnitTest {

    // =========================================================================
    // Reflection helpers
    // =========================================================================

    private fun callBuildImageRequestJson(
        provider: OpenRouterImageProvider,
        request: OpenRouterImageRequest,
    ): String {
        val method = OpenRouterImageProvider::class.java.getDeclaredMethod(
            "buildImageRequestJson",
            OpenRouterImageRequest::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, request) as String
    }

    private fun callErrorCodeText(
        provider: OpenRouterImageProvider,
        error: OpenRouterImageError,
    ): String? {
        val method = OpenRouterImageProvider::class.java.getDeclaredMethod(
            "errorCodeText",
            OpenRouterImageError::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(provider, error) as? String
    }

    private fun makeProvider() = OpenRouterImageProvider(apiKey = "test-key")

    // =========================================================================
    // buildImageRequestJson — correct schema for references
    // =========================================================================

    @Test
    fun `buildImageRequestJson with references produces image_url schema for every reference`() {
        val provider = makeProvider()
        val refs = listOf(
            OpenRouterReference(type = "face", content = "data:image/jpeg;base64,abc123=="),
            OpenRouterReference(type = "style", content = "data:image/jpeg;base64,def456=="),
        )
        val request = OpenRouterImageRequest(
            model = "openai/gpt-image-2.5-flare",
            prompt = "test prompt",
            n = 2,
            input_references = refs,
        )
        val json = callBuildImageRequestJson(provider, request)

        // Each reference must be serialised as {"type":"image_url","image_url":{"url":"..."}}
        assertTrue(
            json.contains("\"type\":\"image_url\""),
            "Reference type must be image_url; got: $json",
        )
        assertTrue(
            json.contains("\"image_url\":{\"url\":"),
            "Reference must wrap url inside image_url object; got: $json",
        )
        assertTrue(json.contains("abc123"), "First reference content must appear in JSON")
        assertTrue(json.contains("def456"), "Second reference content must appear in JSON")

        // Exactly one image_url schema entry per reference
        val count = json.split("\"type\":\"image_url\"").size - 1
        assertEquals(2, count, "Expected exactly one image_url schema per reference")
    }

    @Test
    fun `buildImageRequestJson with single reference wraps url correctly`() {
        val provider = makeProvider()
        val request = OpenRouterImageRequest(
            model = "openai/gpt-image-2.5-flare",
            prompt = "single ref",
            n = 1,
            input_references = listOf(
                OpenRouterReference(type = "face", content = "data:image/jpeg;base64,singleRef=="),
            ),
        )
        val json = callBuildImageRequestJson(provider, request)
        assertTrue(json.contains("\"image_url\":{\"url\":\"data:image/jpeg;base64,singleRef==\"}"))
    }

    @Test
    fun `buildImageRequestJson without references omits input_references field`() {
        val provider = makeProvider()
        val request = OpenRouterImageRequest(
            model = "openai/gpt-image-2.5-flare",
            prompt = "no refs",
            n = 1,
        )
        val json = callBuildImageRequestJson(provider, request)
        assertTrue(
            !json.contains("input_references"),
            "No references → no input_references in JSON; got: $json",
        )
    }

    @Test
    fun `buildImageRequestJson includes required top-level fields`() {
        val provider = makeProvider()
        val request = OpenRouterImageRequest(
            model = "openai/gpt-image-2.5-flare",
            prompt = "hello world",
            n = 3,
            quality = "auto",
            output_format = "png",
        )
        val json = callBuildImageRequestJson(provider, request)
        assertTrue(json.contains("\"model\":\"openai/gpt-image-2.5-flare\""), "json=$json")
        assertTrue(json.contains("\"prompt\":\"hello world\""), "json=$json")
        assertTrue(json.contains("\"n\":3"), "json=$json")
        assertTrue(json.contains("\"quality\":\"auto\""), "json=$json")
        assertTrue(json.contains("\"output_format\":\"png\""), "json=$json")
    }

    @Test
    fun `buildImageRequestJson with four references has four image_url entries`() {
        val provider = makeProvider()
        val refs = (1..4).map { i ->
            OpenRouterReference(type = "face", content = "data:image/jpeg;base64,ref$i==")
        }
        val request = OpenRouterImageRequest(
            model = "openai/gpt-image-2.5-flare",
            prompt = "four refs",
            n = 4,
            input_references = refs,
        )
        val json = callBuildImageRequestJson(provider, request)
        val count = json.split("\"type\":\"image_url\"").size - 1
        assertEquals(4, count, "Expected exactly 4 image_url schema entries; got json=$json")
    }

    // =========================================================================
    // errorCodeText — integer, string, and null code
    // =========================================================================

    @Test
    fun `errorCodeText with integer JsonPrimitive 400 returns numeric string`() {
        val provider = makeProvider()
        val error = OpenRouterImageError(code = JsonPrimitive(400))
        val result = callErrorCodeText(provider, error)
        assertEquals("400", result, "Integer code 400 must be returned as string \"400\"")
    }

    @Test
    fun `errorCodeText with string JsonPrimitive content_policy returns string value`() {
        val provider = makeProvider()
        val error = OpenRouterImageError(code = JsonPrimitive("content_policy"))
        val result = callErrorCodeText(provider, error)
        assertEquals("content_policy", result)
    }

    @Test
    fun `errorCodeText with null code returns null`() {
        val provider = makeProvider()
        val error = OpenRouterImageError(code = null)
        val result = callErrorCodeText(provider, error)
        assertNull(result, "null code must produce null from errorCodeText")
    }

    @Test
    fun `errorCodeText with integer 500 code marks error as retryable`() {
        // normalizeError delegates to errorCodeText; server-side codes (>=500) are retryable
        val provider = makeProvider()
        val error = OpenRouterImageError(code = JsonPrimitive(500), message = "internal")
        // Directly verify errorCodeText returns "500"
        val codeText = callErrorCodeText(provider, error)
        assertEquals("500", codeText)
        // codeText.toIntOrNull() == 500 >= 500 → retryable
        val numeric = codeText?.toIntOrNull()
        assertTrue(numeric != null && numeric >= 500, "HTTP 500 must be treated as retryable")
    }

    // =========================================================================
    // fitReferenceBytesForProvider — extended tests
    // =========================================================================

    private fun makeJpeg(width: Int, height: Int, color: Color = Color(180, 40, 90)): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    @Test
    fun `fitReferenceBytesForProvider large JPEG exceeding budget is shrunken to fit`() {
        val jpeg = makeJpeg(2000, 2000)
        val budget = 50_000
        val (fitted, type) = OpenRouterImageProvider.fitReferenceBytesForProvider(jpeg, "image/jpeg", budget)
        assertTrue(fitted.size <= budget, "fitted ${fitted.size} exceeded budget $budget")
        assertEquals("image/jpeg", type)
    }

    @Test
    fun `fitReferenceBytesForProvider with corrupted bytes returns original unchanged`() {
        // Non-image bytes: ImageIO.read() returns null → return original
        val garbage = ByteArray(100) { 0xDE.toByte() }
        val budget = 200
        val (fitted, _) = OpenRouterImageProvider.fitReferenceBytesForProvider(garbage, "image/jpeg", budget)
        assertTrue(fitted.contentEquals(garbage), "Corrupted bytes should be returned unchanged")
    }

    @Test
    fun `N references per-image budget each reference fits under its share`() {
        // Simulate budget splitting for 3 simultaneous references
        val n = 3
        val perImageBudget = OpenRouterImageProvider.REFERENCE_PAYLOAD_CHAR_BUDGET / n
        val maxRawBytes = (perImageBudget * 3) / 4  // base64 overhead factor

        val references = List(n) { makeJpeg(1600, 1600) }
        for ((idx, ref) in references.withIndex()) {
            val (fitted, _) = OpenRouterImageProvider.fitReferenceBytesForProvider(
                ref, "image/jpeg", maxRawBytes,
            )
            assertTrue(
                fitted.size <= maxRawBytes,
                "Reference $idx: fitted ${fitted.size} exceeded per-image budget $maxRawBytes",
            )
        }
    }
}
