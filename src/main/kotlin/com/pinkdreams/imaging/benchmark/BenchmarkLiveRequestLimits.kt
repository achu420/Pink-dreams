package com.pinkdreams.imaging.benchmark

/**
 * Live OpenRouter catalog ranges are sometimes optimistic.
 * These caps match what the image endpoint actually accepts for Task 32 follow-up.
 */
object BenchmarkLiveRequestLimits {
    fun candidates(slotKey: String, catalogMax: Int?): Int {
        return when (slotKey) {
            "SEEDREAM_5_LITE",
            "SEEDREAM_4_5",
            "QWEN_IMAGE_3_PRO",
            -> 1
            else -> minOf(4, maxOf(1, catalogMax ?: 1))
        }
    }

    fun references(slotKey: String, catalogMax: Int?): Int? {
        return when (slotKey) {
            "QWEN_IMAGE_3_PRO" -> 3
            "FLUX_2_PRO",
            "FLUX_2_KLEIN_4B",
            "RIVERFLOW_2_5_FAST",
            -> 2
            else -> catalogMax
        }
    }
}
