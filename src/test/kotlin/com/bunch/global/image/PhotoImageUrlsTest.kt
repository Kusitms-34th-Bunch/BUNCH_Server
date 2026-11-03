package com.bunch.global.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PhotoImageUrlsTest {
    private val stored = "https://res.cloudinary.com/demo/image/upload/v1700000000/photos/0b9f.jpg"

    @Test
    fun `full size is the stored image itself`() {
        assertEquals(stored, PhotoImageUrls.full(stored))
    }

    @Test
    fun `thumbnail adds a square crop transformation after the upload segment`() {
        assertEquals(
            "https://res.cloudinary.com/demo/image/upload/c_fill,w_300,h_300/v1700000000/photos/0b9f.jpg",
            PhotoImageUrls.thumbnail(stored)
        )
        assertEquals(
            "https://res.cloudinary.com/demo/image/upload/c_fill,w_512,h_512/v1700000000/photos/0b9f.jpg",
            PhotoImageUrls.thumbnail(stored, 512)
        )
    }

    @Test
    fun `thumbnail changes only the first upload segment`() {
        val tricky = "https://res.cloudinary.com/demo/image/upload/v1/photos/image/upload/x.jpg"

        assertEquals(
            "https://res.cloudinary.com/demo/image/upload/c_fill,w_300,h_300/v1/photos/image/upload/x.jpg",
            PhotoImageUrls.thumbnail(tricky)
        )
    }

    @Test
    fun `a URL without an upload segment cannot be transformed and is returned as is`() {
        val other = "https://images.example/photo.jpg"

        assertEquals(other, PhotoImageUrls.thumbnail(other))
        assertEquals(other, PhotoImageUrls.full(other))
    }

    @Test
    fun `thumbnail size must stay within a sane range`() {
        listOf(0, -1, 2561).forEach {
            assertThrows(IllegalArgumentException::class.java) { PhotoImageUrls.thumbnail(stored, it) }
        }
        listOf(1, 2560).forEach { size ->
            val expected = stored.replace("/upload/", "/upload/c_fill,w_$size,h_$size/")
            assertEquals(expected, PhotoImageUrls.thumbnail(stored, size))
        }
    }
}
