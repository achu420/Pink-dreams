package com.pinkdreams.imaging.provider.openrouter

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenRouterReferencePayloadFitTest {
    @Test
    fun largePngIsShrunkUnderTheProviderByteBudget() {
        val image = BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color(180, 40, 90)
            g.fillRect(0, 0, 2400, 1800)
        } finally {
            g.dispose()
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val budget = 80_000
        val (fitted, type) = OpenRouterImageProvider.fitReferenceBytesForProvider(png, "image/png", budget)
        assertTrue(fitted.size <= budget, "fitted ${fitted.size} exceeded $budget")
        assertEquals("image/jpeg", type)
    }

    @Test
    fun jpegAlreadyUnderBudgetIsLeftUnchanged() {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val (fitted, type) = OpenRouterImageProvider.fitReferenceBytesForProvider(jpeg, "image/jpeg", maxRawBytes = jpeg.size + 10)
        assertTrue(fitted.contentEquals(jpeg))
        assertEquals("image/jpeg", type)
    }
}
