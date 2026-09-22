package com.pinkdreams.imaging.job

import com.pinkdreams.persistence.repositories.ImageJobRepository
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically renews a worker's lease while a long provider call is in flight.
 * Stops on [close], on terminal job, or when [ImageJobRepository.renewLease] fails
 * (ownership lost). Does not mutate job status.
 */
class ImageJobLeaseHeartbeat(
    private val jobRepository: ImageJobRepository,
    private val jobId: UUID,
    private val workerName: String,
    private val intervalSeconds: Long,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val lostLease = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "image-lease-heartbeat-$workerName").apply { isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (intervalSeconds <= 0L) return
        future = executor.scheduleWithFixedDelay(
            {
                if (stopped.get()) return@scheduleWithFixedDelay
                try {
                    if (!jobRepository.renewLease(jobId, workerName)) {
                        lostLease.set(true)
                        System.err.println(
                            "IMAGE_HEARTBEAT: lease renew failed job=$jobId worker=$workerName " +
                                "(ownership lost or job terminal) — stopping heartbeat",
                        )
                        stopInternal()
                    }
                } catch (e: Exception) {
                    System.err.println(
                        "IMAGE_HEARTBEAT: renew error job=$jobId worker=$workerName: ${e.message}",
                    )
                }
            },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    fun hasLostLease(): Boolean = lostLease.get()

    override fun close() {
        stopInternal()
        executor.shutdownNow()
    }

    private fun stopInternal() {
        if (!stopped.compareAndSet(false, true)) return
        future?.cancel(false)
    }
}
