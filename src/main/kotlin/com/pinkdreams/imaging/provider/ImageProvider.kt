package com.pinkdreams.imaging.provider

interface ImageProvider {
    val providerId: String
    val capabilities: ProviderCapabilities

    suspend fun submit(request: GenerationRequest): GenerationResult

    suspend fun getStatus(jobHandle: ProviderJobHandle): GenerationResult

    suspend fun getResult(jobHandle: ProviderJobHandle): GenerationResult

    suspend fun cancel(jobHandle: ProviderJobHandle): Boolean
}
