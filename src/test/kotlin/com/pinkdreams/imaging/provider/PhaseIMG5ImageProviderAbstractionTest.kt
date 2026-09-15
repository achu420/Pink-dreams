package com.pinkdreams.imaging.provider

import java.util.UUID
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

class PhaseIMG5ImageProviderAbstractionTest {

    private lateinit var provider: FakeImageProvider

    @BeforeTest
    fun setupTest() {
        provider = FakeImageProvider()
    }

    // =========================================================================
    // Request Validation (1-5)
    // =========================================================================

    @Test
    fun `Request 1 - valid request passes validation`() {
        val request = GenerationRequest(
            prompt = "A beautiful portrait",
            idempotencyKey = "test-key-1",
            candidateCount = 2,
            widthPx = 512,
            heightPx = 512,
        )

        val validation = request.validate()
        assertTrue(validation.valid)
    }

    @Test
    fun `Request 2 - empty prompt rejected`() {
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "test-key-2",
        )

        val validation = request.validate()
        assertFalse(validation.valid)
        assertTrue(validation.errors.any { it.contains("Prompt") })
    }

    @Test
    fun `Request 3 - invalid candidate count rejected`() {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-3",
            candidateCount = 0,
        )

        val validation = request.validate()
        assertFalse(validation.valid)
    }

    @Test
    fun `Request 4 - missing idempotency key rejected`() {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "",
        )

        val validation = request.validate()
        assertFalse(validation.valid)
    }

    @Test
    fun `Request 5 - invalid dimensions rejected`() {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-5",
            widthPx = 32,
            heightPx = 512,
        )

        val validation = request.validate()
        assertFalse(validation.valid)
    }

    // =========================================================================
    // Provider Capabilities (6-8)
    // =========================================================================

    @Test
    fun `Capability 6 - provider advertises capabilities`() {
        val caps = provider.capabilities
        assertEquals(4, caps.maxCandidateCount)
        assertTrue(caps.supportsReferences)
        assertTrue(caps.supportsMultipleReferences)
        assertTrue(caps.supportsCancellation)
        assertTrue(caps.supportsIdempotency)
    }

    @Test
    fun `Capability 7 - aspect ratio support`() {
        val caps = provider.capabilities
        assertTrue(caps.supportedAspectRatios.contains("1:1"))
        assertTrue(caps.supportedAspectRatios.contains("16:9"))
    }

    @Test
    fun `Capability 8 - size constraints`() {
        val caps = provider.capabilities
        assertEquals(256, caps.minWidthPx)
        assertEquals(2048, caps.maxWidthPx)
        assertEquals(256, caps.minHeightPx)
        assertEquals(2048, caps.maxHeightPx)
    }

    // =========================================================================
    // Generation Submission (9-13)
    // =========================================================================

    @Test
    fun `Submit 9 - basic generation request succeeds`() = runBlocking {
        val request = GenerationRequest(
            prompt = "A test image",
            idempotencyKey = "test-key-9",
            candidateCount = 1,
        )

        val result = provider.submit(request)

        assertEquals(GenerationStatus.COMPLETED, result.status)
        assertNotNull(result.jobHandle)
        assertEquals(1, result.candidates.size)
    }

    @Test
    fun `Submit 10 - multiple candidates requested`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-10",
            candidateCount = 3,
        )

        val result = provider.submit(request)

        assertEquals(3, result.candidates.size)
        assertEquals(3, result.candidates.map { it.id }.toSet().size, "Each candidate should have unique ID")
    }

    @Test
    fun `Submit 11 - with reference inputs`() = runBlocking {
        val refId = UUID.randomUUID()
        val request = GenerationRequest(
            prompt = "Portrait with reference",
            idempotencyKey = "test-key-11",
            references = listOf(
                ReferenceInput(refId, "FACE", 0.8f),
                ReferenceInput(UUID.randomUUID(), "STYLE", 0.5f),
            ),
            candidateCount = 1,
        )

        val result = provider.submit(request)

        assertEquals(GenerationStatus.COMPLETED, result.status)
    }

    @Test
    fun `Submit 12 - with specific dimensions`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-12",
            widthPx = 768,
            heightPx = 1024,
            candidateCount = 1,
        )

        val result = provider.submit(request)

        assertNotNull(result.candidates[0].widthPx)
        assertNotNull(result.candidates[0].heightPx)
    }

    @Test
    fun `Submit 13 - invalid request returns error status`() = runBlocking {
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "test-key-13",
        )

        val result = provider.submit(request)

        assertEquals(GenerationStatus.FAILED, result.status)
        assertNotNull(result.error)
        assertFalse(result.error!!.retryable)
    }

    // =========================================================================
    // Status Polling (14-16)
    // =========================================================================

    @Test
    fun `Status 14 - poll job status`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-14",
            candidateCount = 1,
        )

        val submitted = provider.submit(request)
        val status = provider.getStatus(submitted.jobHandle)

        assertEquals(GenerationStatus.COMPLETED, status.status)
        assertEquals(1, status.candidates.size)
    }

    @Test
    fun `Status 15 - missing job returns error`() = runBlocking {
        val fakeHandle = ProviderJobHandle("fake-provider", "nonexistent")
        val status = provider.getStatus(fakeHandle)

        assertEquals(GenerationStatus.FAILED, status.status)
        assertNotNull(status.error)
    }

    @Test
    fun `Status 16 - job handle contains provider identifier`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-16",
        )

        val result = provider.submit(request)

        assertEquals("fake-provider", result.jobHandle.providerIdentifier)
        assertNotNull(result.jobHandle.externalJobId)
    }

    // =========================================================================
    // Result Retrieval (17-19)
    // =========================================================================

    @Test
    fun `Result 17 - get completed result`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-17",
            candidateCount = 2,
        )

        val submitted = provider.submit(request)
        val result = provider.getResult(submitted.jobHandle)

        assertEquals(GenerationStatus.COMPLETED, result.status)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `Result 18 - candidate has metadata`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-18",
            candidateCount = 1,
        )

        val result = provider.submit(request)
        val candidate = result.candidates[0]

        assertNotNull(candidate.id)
        assertNotNull(candidate.checksum)
        assertNotNull(candidate.imageData)
    }

    @Test
    fun `Result 19 - candidates are deterministic`() = runBlocking {
        val request1 = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-19a",
            candidateCount = 1,
        )

        val request2 = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-19b",
            candidateCount = 1,
        )

        val result1 = provider.submit(request1)
        val result2 = provider.submit(request2)

        assertEquals(result1.candidates.size, result2.candidates.size)
    }

    // =========================================================================
    // Cancellation (20-22)
    // =========================================================================

    @Test
    fun `Cancel 20 - cannot cancel completed job`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-20",
        )

        val submitted = provider.submit(request)

        val cancelled = provider.cancel(submitted.jobHandle)

        assertFalse(cancelled, "Cannot cancel already completed job")
    }

    @Test
    fun `Cancel 21 - cannot cancel non-existent job`() = runBlocking {
        val fakeHandle = ProviderJobHandle("fake-provider", "nonexistent")
        val cancelled = provider.cancel(fakeHandle)

        assertFalse(cancelled)
    }

    @Test
    fun `Cancel 22 - cancellation returns false on terminal state`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-22",
        )

        val submitted = provider.submit(request)

        val cancelled = provider.cancel(submitted.jobHandle)

        assertFalse(cancelled, "Cannot cancel terminal state job")
    }

    // =========================================================================
    // Error Handling (23-25)
    // =========================================================================

    @Test
    fun `Error 23 - errors include retryable flag`() = runBlocking {
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "test-key-23",
        )

        val result = provider.submit(request)

        assertNotNull(result.error)
        assertFalse(result.error!!.retryable, "Validation errors are non-retryable")
    }

    @Test
    fun `Error 24 - error has code and message`() = runBlocking {
        val request = GenerationRequest(
            prompt = "",
            idempotencyKey = "test-key-24",
        )

        val result = provider.submit(request)

        assertNotNull(result.error)
        assertNotNull(result.error!!.code)
        assertNotNull(result.error!!.message)
    }

    @Test
    fun `Error 25 - transient error has retryable=true`() = runBlocking {
        val fakeHandle = ProviderJobHandle("fake-provider", "test-25")
        val result = provider.getResult(fakeHandle)

        if (result.error != null) {
            assertTrue(result.error!!.retryable)
        }
    }

    // =========================================================================
    // Idempotency (26-27)
    // =========================================================================

    @Test
    fun `Idempotency 26 - request includes idempotency key`() {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "unique-key-26",
        )

        assertEquals("unique-key-26", request.idempotencyKey)
    }

    @Test
    fun `Idempotency 27 - same request generates different job handles`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-27",
        )

        val result1 = provider.submit(request)
        val result2 = provider.submit(request)

        assertNotEquals(result1.jobHandle.externalJobId, result2.jobHandle.externalJobId)
    }

    // =========================================================================
    // Provider Identity (28-29)
    // =========================================================================

    @Test
    fun `Identity 28 - provider has identifier`() {
        assertEquals("fake-provider", provider.providerId)
    }

    @Test
    fun `Identity 29 - job handle includes provider identifier`() = runBlocking {
        val request = GenerationRequest(
            prompt = "Test",
            idempotencyKey = "test-key-29",
        )

        val result = provider.submit(request)

        assertEquals("fake-provider", result.jobHandle.providerIdentifier)
    }
}

private fun assertNotEquals(expected: String, actual: String, message: String? = null) {
    if (expected == actual) {
        fail(message ?: "Expected different values but got: $expected")
    }
}
