package com.pinkdreams.imaging.job

sealed class ImageJobResult {
    data class Success(val metadata: String = "") : ImageJobResult()
    data class Failure(val errorMessage: String, val retryable: Boolean = true) : ImageJobResult()
}

interface ImageJobHandler {
    fun handle(job: ImageJob): ImageJobResult
}

class NoOpImageJobHandler : ImageJobHandler {
    override fun handle(job: ImageJob): ImageJobResult {
        return ImageJobResult.Success(metadata = "no-op handler")
    }
}
