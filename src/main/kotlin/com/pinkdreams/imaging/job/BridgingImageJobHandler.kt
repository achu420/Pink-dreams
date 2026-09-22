package com.pinkdreams.imaging.job

import com.pinkdreams.imaging.orchestration.ImageGenerationHandler
import kotlinx.coroutines.runBlocking

/**
 * Bridges the sync [ImageJobHandler] worker contract to the suspend
 * [ImageGenerationHandler.handleAsync] implementation.
 *
 * The worker runs on a dedicated background thread, so [runBlocking] is
 * acceptable and does not block the Ktor event loop.
 */
class BridgingImageJobHandler(
    private val generationHandler: ImageGenerationHandler,
) : ImageJobHandler {
    override fun handle(job: ImageJob): ImageJobResult = runBlocking {
        generationHandler.handleAsync(job)
    }
}
