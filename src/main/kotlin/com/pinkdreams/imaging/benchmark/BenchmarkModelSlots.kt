package com.pinkdreams.imaging.benchmark

/**
 * Ten named benchmark slots. Matching uses live OpenRouter IDs only.
 * No silent substitution when a slot is missing from the catalog.
 */
object BenchmarkModelSlots {
    data class Slot(
        val slotKey: String,
        val displayName: String,
        val exactIds: List<String>,
        val idContains: List<String>,
        val nameContains: List<String>,
    )

    val ALL: List<Slot> = listOf(
        Slot(
            slotKey = "SEEDREAM_5_PRO",
            displayName = "Seedream 5.0 Pro",
            exactIds = listOf("bytedance-seed/seedream-5-0-pro"),
            idContains = listOf("seedream-5-0-pro", "seedream-5.0-pro"),
            nameContains = listOf("seedream 5.0 pro", "seedream 5 pro"),
        ),
        Slot(
            slotKey = "SEEDREAM_5_LITE",
            displayName = "Seedream 5.0 Lite",
            exactIds = listOf("bytedance-seed/seedream-5-0-lite"),
            idContains = listOf("seedream-5-0-lite", "seedream-5.0-lite"),
            nameContains = listOf("seedream 5.0 lite", "seedream 5 lite"),
        ),
        Slot(
            slotKey = "SEEDREAM_4_5",
            displayName = "Seedream 4.5",
            exactIds = listOf("bytedance-seed/seedream-4.5"),
            idContains = listOf("seedream-4.5", "seedream-4-5"),
            nameContains = listOf("seedream 4.5"),
        ),
        Slot(
            slotKey = "FLUX_2_PRO",
            displayName = "FLUX.2 Pro",
            exactIds = listOf("black-forest-labs/flux.2-pro"),
            idContains = listOf("flux.2-pro"),
            nameContains = listOf("flux.2 pro"),
        ),
        Slot(
            slotKey = "FLUX_2_KLEIN_4B",
            displayName = "FLUX.2 Klein 4B",
            exactIds = listOf("black-forest-labs/flux.2-klein-4b"),
            idContains = listOf("flux.2-klein-4b"),
            nameContains = listOf("flux.2 klein 4b"),
        ),
        Slot(
            slotKey = "GPT_IMAGE_2",
            displayName = "GPT Image 2",
            exactIds = listOf("openai/gpt-image-2"),
            idContains = emptyList(),
            nameContains = listOf("gpt image 2"),
        ),
        Slot(
            slotKey = "NANO_BANANA_2_LITE",
            displayName = "Nano Banana 2 Lite",
            exactIds = listOf("google/gemini-3.1-flash-lite-image"),
            idContains = listOf("gemini-3.1-flash-lite-image"),
            nameContains = listOf("nano banana 2 lite"),
        ),
        Slot(
            slotKey = "RIVERFLOW_2_5_FAST",
            displayName = "Riverflow 2.5 Fast",
            exactIds = listOf("sourceful/riverflow-v2.5-fast"),
            idContains = listOf("riverflow-v2.5-fast"),
            nameContains = listOf("riverflow v2.5 fast", "riverflow 2.5 fast"),
        ),
        Slot(
            slotKey = "MUSE_IMAGE",
            displayName = "Muse Image",
            exactIds = listOf("meta/muse-image"),
            idContains = listOf("muse-image"),
            nameContains = listOf("muse image"),
        ),
        Slot(
            slotKey = "QWEN_IMAGE_3_PRO",
            displayName = "Qwen Image 3 Pro",
            exactIds = listOf("qwen/qwen-image-3-pro"),
            idContains = listOf("qwen-image-3-pro"),
            nameContains = listOf("qwen image 3 pro"),
        ),
    )

    fun match(
        slot: Slot,
        catalog: List<com.pinkdreams.imaging.provider.openrouter.OpenRouterImageModelCatalog.DiscoveredModel>,
    ): com.pinkdreams.imaging.provider.openrouter.OpenRouterImageModelCatalog.DiscoveredModel? {
        catalog.firstOrNull { it.modelId in slot.exactIds }?.let { return it }
        if (slot.slotKey == "GPT_IMAGE_2") {
            return catalog.firstOrNull { it.modelId.equals("openai/gpt-image-2", ignoreCase = true) }
        }
        catalog.firstOrNull { model ->
            slot.idContains.any { token -> model.modelId.contains(token, ignoreCase = true) }
        }?.let { return it }
        return catalog.firstOrNull { model ->
            slot.nameContains.any { token -> model.displayName.contains(token, ignoreCase = true) }
        }
    }
}
