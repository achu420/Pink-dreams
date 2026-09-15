package com.pinkdreams.imaging.job

enum class ImageJobStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    fun isTerminal(): Boolean = this in listOf(SUCCEEDED, FAILED, CANCELLED)
    fun isTransient(): Boolean = this in listOf(RUNNING, RETRY_WAIT)
}
