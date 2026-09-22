package com.pinkdreams.imaging.job

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background loop that drives [ImageJobWorker] until [stop] is called.
 */
class ImageJobWorkerRuntime(
    private val worker: ImageJobWorker,
    private val pollIntervalMs: Long = System.getenv("IMAGE_WORKER_POLL_MS")?.toLongOrNull() ?: 2_000L,
    private val batchSize: Int = System.getenv("IMAGE_WORKER_BATCH")?.toIntOrNull() ?: 5,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "image-job-worker").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({
            try {
                worker.recoverStaleLeasedJobs()
                worker.processRetryableJobs(batchSize)
                worker.processPendingJobs(batchSize)
            } catch (e: Exception) {
                System.err.println("image-job-worker tick failed: ${e.message}")
            }
        }, 500L, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        executor.shutdownNow()
    }
}
