package com.pinkdreams.chat.imaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BestEffortImageEnqueueTest {

    @Test
    fun `heuristic detects show me and selfie`() {
        assertTrue(BestEffortImageEnqueue.looksLikeImageRequest("Show me on a beach", null))
        assertTrue(BestEffortImageEnqueue.looksLikeImageRequest("send a pic please", null))
        assertTrue(BestEffortImageEnqueue.looksLikeImageRequest("selfie?", null))
        assertTrue(BestEffortImageEnqueue.looksLikeImageRequest("hello", "image_share"))
    }

    @Test
    fun `heuristic ignores ordinary chat`() {
        assertFalse(BestEffortImageEnqueue.looksLikeImageRequest("how was your day?", null))
        assertFalse(BestEffortImageEnqueue.looksLikeImageRequest("I miss you", "companionship"))
    }
}
