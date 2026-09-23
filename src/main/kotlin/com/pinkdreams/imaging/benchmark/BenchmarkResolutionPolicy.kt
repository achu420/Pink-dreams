package com.pinkdreams.imaging.benchmark

/**
 * Common benchmark resolution is 1K / 1024×1024 when the model lists it.
 * Otherwise use the closest supported tier and record the deviation.
 */
object BenchmarkResolutionPolicy {
    const val COMMON_TIER = "1K"
    const val COMMON_WIDTH = 1024
    const val COMMON_HEIGHT = 1024

    data class ResolutionChoice(
        val requestedResolution: String,
        val actualResolution: String,
        val widthPx: Int,
        val heightPx: Int,
        val deviation: String?,
    )

    fun choose(supportedResolutions: List<String>): ResolutionChoice {
        val tiers = supportedResolutions.map { it.trim() }.filter { it.isNotBlank() }
        if (tiers.isEmpty()) {
            return ResolutionChoice(
                requestedResolution = COMMON_TIER,
                actualResolution = COMMON_TIER,
                widthPx = COMMON_WIDTH,
                heightPx = COMMON_HEIGHT,
                deviation = "RESOLUTION_ENUM_NOT_PUBLISHED",
            )
        }
        if (COMMON_TIER in tiers || tiers.any { it.equals("1024", true) }) {
            return ResolutionChoice(
                requestedResolution = COMMON_TIER,
                actualResolution = COMMON_TIER,
                widthPx = COMMON_WIDTH,
                heightPx = COMMON_HEIGHT,
                deviation = null,
            )
        }
        val closest = closestTier(tiers)
        val px = pixelsForTier(closest)
        return ResolutionChoice(
            requestedResolution = COMMON_TIER,
            actualResolution = closest,
            widthPx = px,
            heightPx = px,
            deviation = "COMMON_1K_UNSUPPORTED_USED_$closest",
        )
    }

    private fun closestTier(tiers: List<String>): String {
        val rank = mapOf("512" to 512, "1K" to 1024, "2K" to 2048, "4K" to 4096)
        return tiers.minByOrNull { t ->
            kotlin.math.abs((rank[t] ?: 1024) - 1024)
        } ?: tiers.first()
    }

    private fun pixelsForTier(tier: String): Int = when (tier.uppercase()) {
        "512" -> 512
        "1K" -> 1024
        "2K" -> 2048
        "4K" -> 4096
        else -> 1024
    }
}
