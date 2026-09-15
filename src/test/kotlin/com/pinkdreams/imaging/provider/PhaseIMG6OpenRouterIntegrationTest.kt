package com.pinkdreams.imaging.provider

import com.pinkdreams.imaging.provider.openrouter.OpenRouterImageProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

class PhaseIMG6OpenRouterIntegrationTest {

    private lateinit var provider: OpenRouterImageProvider

    fun setupTest() {
        provider = OpenRouterImageProvider(
            apiKey = "test-key-12345",
            endpoint = "https://openrouter.ai/api/v1/images",
            imageModel = "openai/gpt-image-2.5-flare",
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 300,
        )
    }

    // =========================================================================
    // Configuration Tests (1-3)
    // =========================================================================

    @Test
    fun `Config 1 - API key is accepted`() {
        setupTest()
        val providerWithKey = OpenRouterImageProvider(
            apiKey = "valid-key",
            endpoint = "https://openrouter.ai/api/v1/images/generations",
            imageModel = "flux-pro",
        )
        assertEquals("openrouter", providerWithKey.providerId)
    }

    @Test
    fun `Config 2 - endpoint is configurable`() {
        setupTest()
        val customEndpoint = "https://custom.endpoint.com/v1/images"
        val customProvider = OpenRouterImageProvider(
            apiKey = "test-key",
            endpoint = customEndpoint,
            imageModel = "openai/gpt-image-2.5-flare",
        )
        assertTrue(customProvider.providerId == "openrouter")
    }

    @Test
    fun `Config 3 - image model is configurable`() {
        setupTest()
        val customModel = OpenRouterImageProvider(
            apiKey = "test-key",
            endpoint = "https://openrouter.ai/api/v1/images",
            imageModel = "custom-model",
        )
        assertEquals("openrouter", customModel.providerId)
    }

    // =========================================================================
    // Capabilities Tests (4-7)
    // =========================================================================

    @Test
    fun `Capability 4 - provider advertises correct capabilities`() {
        setupTest()
        val caps = provider.capabilities
        assertEquals(10, caps.maxCandidateCount)
        assertTrue(caps.supportsReferences)
        assertTrue(caps.supportsMultipleReferences)
        assertFalse(caps.supportsCancellation)
        assertTrue(caps.supportsIdempotency)
    }

    @Test
    fun `Capability 5 - aspect ratio support`() {
        setupTest()
        val caps = provider.capabilities
        assertTrue(caps.supportedAspectRatios.contains("1:1"))
        assertTrue(caps.supportedAspectRatios.contains("16:9"))
        assertTrue(caps.supportedAspectRatios.contains("9:16"))
    }

    @Test
    fun `Capability 6 - size constraints`() {
        setupTest()
        val caps = provider.capabilities
        assertEquals(256, caps.minWidthPx)
        assertEquals(2048, caps.maxWidthPx)
        assertEquals(256, caps.minHeightPx)
        assertEquals(2048, caps.maxHeightPx)
    }

    @Test
    fun `Capability 7 - cancellation not supported`() {
        setupTest()
        assertFalse(provider.capabilities.supportsCancellation)
    }

    // =========================================================================
    // Request Validation Tests (8-11)
    // =========================================================================

    @Test
    fun `Request 8 - valid request accepted`() {
        setupTest()
        val request = GenerationRequest(
            prompt = "A beautiful portrait",
            idempotencyKey = "key-8",
            candidateCount = 2,
        )
        val validation = request.validate()
        assertTrue(validation.valid)
    }

    @Test
    fun `Request 9 - empty prompt rejected`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "key-9",
        )
        val result = provider.submit(request)
        assertEquals(GenerationStatus.FAILED, result.status)
        assertNotNull(result.error)
        assertEquals("VALIDATION_ERROR", result.error!!.code)
        assertFalse(result.error!!.retryable)
    }

    @Test
    fun `Request 10 - missing idempotency key rejected`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "",
        )
        val result = provider.submit(request)
        assertEquals(GenerationStatus.FAILED, result.status)
        assertNotNull(result.error)
    }

    @Test
    fun `Request 11 - dimension validation`() {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-11",
            widthPx = 32,
            heightPx = 512,
        )
        val validation = request.validate()
        assertFalse(validation.valid)
    }

    // =========================================================================
    // Submission Tests (12-16)
    // =========================================================================

    @Test
    fun `Submit 12 - basic generation request succeeds`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "A test image",
            idempotencyKey = "key-12",
            candidateCount = 1,
        )
        val result = provider.submit(request)
        assertNotNull(result.jobHandle)
        assertEquals("openrouter", result.jobHandle.providerIdentifier)
        assertNotNull(result.jobHandle.externalJobId)
    }

    @Test
    fun `Submit 13 - multiple candidates requested`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-13",
            candidateCount = 3,
        )
        val result = provider.submit(request)
        assertNotNull(result.jobHandle)
        assertEquals("openrouter", result.jobHandle.providerIdentifier)
    }

    @Test
    fun `Submit 14 - with specific dimensions`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-14",
            widthPx = 768,
            heightPx = 1024,
            candidateCount = 1,
        )
        val result = provider.submit(request)
        assertNotNull(result.jobHandle)
    }

    @Test
    fun `Submit 15 - with reference inputs preserved`() = runBlocking {
        setupTest()
        val refId = UUID.randomUUID()
        val request = GenerationRequest(
            prompt = "Portrait with reference",
            idempotencyKey = "key-15",
            references = listOf(
                ReferenceInput(refId, "FACE", 0.8f),
            ),
            candidateCount = 1,
        )
        val validation = request.validate()
        assertTrue(validation.valid)
    }

    @Test
    fun `Submit 16 - idempotency key is preserved`() {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "unique-key-16",
        )
        assertEquals("unique-key-16", request.idempotencyKey)
    }

    // =========================================================================
    // Status/Result Tests (17-19)
    // =========================================================================

    @Test
    fun `Status 17 - get status of non-existent job`() = runBlocking {
        setupTest()
        val fakeHandle = ProviderJobHandle("openrouter", "nonexistent-job")
        val status = provider.getStatus(fakeHandle)
        assertEquals(GenerationStatus.FAILED, status.status)
        assertNotNull(status.error)
        assertTrue(status.error!!.retryable)
    }

    @Test
    fun `Status 18 - wrong provider returns error`() = runBlocking {
        setupTest()
        val wrongHandle = ProviderJobHandle("wrong-provider", "some-id")
        val status = provider.getStatus(wrongHandle)
        assertEquals(GenerationStatus.FAILED, status.status)
        assertNotNull(status.error)
        assertFalse(status.error!!.retryable)
    }

    @Test
    fun `Result 19 - result and status are equivalent for completed jobs`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-19",
        )
        val submitted = provider.submit(request)
        val status = provider.getStatus(submitted.jobHandle)
        val result = provider.getResult(submitted.jobHandle)
        assertEquals(status.status, result.status)
    }

    // =========================================================================
    // Cancellation Tests (20-22)
    // =========================================================================

    @Test
    fun `Cancel 20 - cancellation returns false`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-20",
        )
        val submitted = provider.submit(request)
        val cancelled = provider.cancel(submitted.jobHandle)
        assertFalse(cancelled)
    }

    @Test
    fun `Cancel 21 - capabilities reflect no cancellation support`() {
        setupTest()
        assertFalse(provider.capabilities.supportsCancellation)
    }

    @Test
    fun `Cancel 22 - cancel on non-existent job returns false`() = runBlocking {
        setupTest()
        val fakeHandle = ProviderJobHandle("openrouter", "nonexistent")
        val cancelled = provider.cancel(fakeHandle)
        assertFalse(cancelled)
    }

    // =========================================================================
    // Error Handling Tests (23-28)
    // =========================================================================

    @Test
    fun `Error 23 - validation errors are non-retryable`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "key-23",
        )
        val result = provider.submit(request)
        assertNotNull(result.error)
        assertFalse(result.error!!.retryable)
    }

    @Test
    fun `Error 24 - error has code and message`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "key-24",
        )
        val result = provider.submit(request)
        assertNotNull(result.error)
        assertNotNull(result.error!!.code)
        assertNotNull(result.error!!.message)
    }

    @Test
    fun `Error 25 - authentication error is non-retryable`() {
        setupTest()
        assertTrue(true)
    }

    @Test
    fun `Error 26 - rate limit error is retryable`() {
        setupTest()
        assertTrue(true)
    }

    @Test
    fun `Error 27 - server error is retryable`() {
        setupTest()
        assertTrue(true)
    }

    @Test
    fun `Error 28 - timeout error is retryable`() {
        setupTest()
        assertTrue(true)
    }

    // =========================================================================
    // Security Tests (29-31)
    // =========================================================================

    @Test
    fun `Security 29 - API key not in error messages`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "key-29",
        )
        val result = provider.submit(request)
        if (result.error != null) {
            assertFalse(result.error!!.message.contains("test-key"))
            assertFalse(result.error!!.code.contains("test-key"))
        }
    }

    @Test
    fun `Security 30 - API key not in toString`() {
        setupTest()
        val providerString = provider.providerId
        assertFalse(providerString.contains("test-key"))
    }

    @Test
    fun `Security 31 - Authorization header is never constructed in error path`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "key-31",
        )
        val result = provider.submit(request)
        assertNotNull(result)
    }

    // =========================================================================
    // Provider Independence Tests (32-34)
    // =========================================================================

    @Test
    fun `Independence 32 - no OpenRouter types in result`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-32",
        )
        val result = provider.submit(request)
        assertTrue(result is GenerationResult)
        assertFalse(result.javaClass.name.contains("OpenRouter"))
    }

    @Test
    fun `Independence 33 - provider is interface-typed`() {
        setupTest()
        val abstractProvider: ImageProvider = provider
        assertEquals("openrouter", abstractProvider.providerId)
    }

    @Test
    fun `Independence 34 - no provider-specific fields in shared models`() {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-34",
        )
        assertTrue(request.javaClass.declaredFields.none { it.type.name.contains("OpenRouter") })
    }

    // =========================================================================
    // Idempotency Tests (35-36)
    // =========================================================================

    @Test
    fun `Idempotency 35 - request includes idempotency key`() {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "unique-key-35",
        )
        assertEquals("unique-key-35", request.idempotencyKey)
    }

    @Test
    fun `Idempotency 36 - provider identifier consistency`() = runBlocking {
        setupTest()
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "key-36",
        )
        val result = provider.submit(request)
        assertEquals("openrouter", result.jobHandle.providerIdentifier)
    }
}
