package com.warehouse.stockchecker.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxTransformTest {

    @Test
    fun `square frame needs no padding`() {
        val transform = LetterboxTransform.of(480, 480, 640)

        assertEquals(640, transform.scaledWidth)
        assertEquals(640, transform.scaledHeight)
        assertEquals(0f, transform.padX, 1e-6f)
        assertEquals(0f, transform.padY, 1e-6f)
    }

    @Test
    fun `landscape frame is padded vertically`() {
        val transform = LetterboxTransform.of(1280, 720, 640)

        assertEquals(0.5f, transform.scale, 1e-6f)
        assertEquals(640, transform.scaledWidth)
        assertEquals(360, transform.scaledHeight)
        assertEquals(0f, transform.padX, 1e-6f)
        assertEquals(140f, transform.padY, 1e-6f)
    }

    @Test
    fun `portrait frame is padded horizontally`() {
        val transform = LetterboxTransform.of(720, 1280, 640)

        assertEquals(360, transform.scaledWidth)
        assertEquals(640, transform.scaledHeight)
        assertEquals(140f, transform.padX, 1e-6f)
        assertEquals(0f, transform.padY, 1e-6f)
    }

    @Test
    fun `full content box maps back to the whole source frame`() {
        val transform = LetterboxTransform.of(1280, 720, 640)
        // The image content occupies y in [140, 500] of the 640px canvas.
        val mapped = transform.toSourceNormalized(
            BoxF(left = 0f, top = 140f / 640f, right = 1f, bottom = 500f / 640f)
        )

        assertEquals(0f, mapped.left, 1e-4f)
        assertEquals(0f, mapped.top, 1e-4f)
        assertEquals(1f, mapped.right, 1e-4f)
        assertEquals(1f, mapped.bottom, 1e-4f)
    }

    @Test
    fun `centered box stays centered after mapping`() {
        val transform = LetterboxTransform.of(1280, 720, 640)
        val mapped = transform.toSourceNormalized(BoxF(0.4f, 0.4f, 0.6f, 0.6f))

        assertEquals(0.5f, mapped.centerX, 1e-3f)
        assertEquals(0.5f, mapped.centerY, 1e-3f)
    }

    @Test
    fun `mapping clamps values into the unit range`() {
        val transform = LetterboxTransform.of(720, 1280, 640)
        val mapped = transform.toSourceNormalized(BoxF(-0.5f, -0.5f, 1.5f, 1.5f))

        assertEquals(0f, mapped.left, 1e-6f)
        assertEquals(0f, mapped.top, 1e-6f)
        assertEquals(1f, mapped.right, 1e-6f)
        assertEquals(1f, mapped.bottom, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non positive dimensions`() {
        LetterboxTransform.of(0, 720, 640)
    }
}
