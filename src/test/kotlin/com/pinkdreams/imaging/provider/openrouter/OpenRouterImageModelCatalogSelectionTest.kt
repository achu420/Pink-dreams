package com.pinkdreams.imaging.provider.openrouter

import kotlin.test.Test
import kotlin.test.assertTrue

class OpenRouterImageModelCatalogSelectionTest {
    @Test
    fun `selected evaluation models are non-empty and distinct`() {
        val selected = OpenRouterImageModelCatalog.SELECTED_FOR_EVALUATION
        assertTrue(selected.size in 2..3)
        assertTrue(selected.distinct().size == selected.size)
        assertTrue(selected.contains("openai/gpt-image-2.5-flare"))
    }
}
