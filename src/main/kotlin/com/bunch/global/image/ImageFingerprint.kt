package com.bunch.global.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * A 64-bit value that sums up what an image looks like, so two shots of nearly the same scene can be told apart from
 * two different pictures (photo box 4.2.8 rule 1). It is a difference hash: the image is shrunk to a grid of 9 by 8
 * brightness values, and each bit tells whether a cell is brighter than the cell on its right. The values of two
 * similar images differ in only a few bits, so the number of different bits is the distance between them.
 *
 * No AI and no library is used, only the image reader of the JDK. The image is read at a reduced size, so a large
 * photo is not decoded in full.
 */
object ImageFingerprint {
    private const val COLUMNS = 9
    private const val ROWS = 8
    private const val READ_SIZE = 512

    /** The value of the image, or null when the bytes are not an image the JDK can read. */
    fun of(bytes: ByteArray): Long? = try {
        read(bytes)?.let(::hash)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun read(bytes: ByteArray): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        return input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val step = maxOf(1, maxOf(reader.getWidth(0), reader.getHeight(0)) / READ_SIZE)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun hash(image: BufferedImage): Long {
        val cells = brightness(image)
        var value = 0L
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS - 1) {
                value = value shl 1
                if (cells[row][column] > cells[row][column + 1]) value = value or 1L
            }
        }
        return value
    }

    // The mean brightness of each cell of the grid. Every cell covers at least one pixel, even in a tiny image.
    private fun brightness(image: BufferedImage): Array<DoubleArray> =
        Array(ROWS) { row ->
            val top = bound(row, ROWS, image.height)
            val bottom = maxOf(bound(row + 1, ROWS, image.height), top + 1)
            DoubleArray(COLUMNS) { column ->
                val left = bound(column, COLUMNS, image.width)
                val right = maxOf(bound(column + 1, COLUMNS, image.width), left + 1)
                mean(image, left, right, top, bottom)
            }
        }

    // The first pixel of the part with this index when the length is cut into equal parts. The end is the length.
    private fun bound(index: Int, parts: Int, length: Int): Int = if (index >= parts) length else index * length / parts

    private fun mean(image: BufferedImage, left: Int, right: Int, top: Int, bottom: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in top until minOf(bottom, image.height)) {
            for (x in left until minOf(right, image.width)) {
                val rgb = image.getRGB(x, y)
                sum += 0.299 * (rgb shr 16 and 0xFF) + 0.587 * (rgb shr 8 and 0xFF) + 0.114 * (rgb and 0xFF)
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
}
