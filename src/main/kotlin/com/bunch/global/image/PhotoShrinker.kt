package com.bunch.global.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a photo so that its long side is at most a given size, and writes it again as a JPEG (recommended bundle
 * specification 4-2: the photo is sent to the AI at about 1024 pixels on the long side). Only the pixels are written:
 * everything else that the file carried (the camera, the time, the place) is left behind, so it is not sent on.
 *
 * Only the image reader of the JDK is used, as for the quality of a photo ([ImageQuality]). A photo is read at a
 * reduced size, so a large photo is not decoded in full. A photo that is already small enough is written again as it
 * is, so the output never carries the metadata of the input.
 */
object PhotoShrinker {
    private const val JPEG_QUALITY = 0.85f

    /** The shrunk photo, or null when [bytes] is not an image that can be read. */
    fun shrink(bytes: ByteArray, longSide: Int): PhotoImage? {
        require(longSide > 0) { "the long side must be positive" }
        return try {
            read(bytes, longSide)?.let { toPhoto(scaled(it, longSide)) }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun read(bytes: ByteArray, longSide: Int): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        return input.use {
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val step = max(1, max(reader.getWidth(0), reader.getHeight(0)) / longSide)
                val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    }

    // The image as RGB without transparency, which a JPEG needs, at the size where the long side is at most [longSide].
    private fun scaled(source: BufferedImage, longSide: Int): BufferedImage {
        val scale = minOf(1.0, longSide.toDouble() / max(source.width, source.height))
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return rgb
    }

    private fun toPhoto(image: BufferedImage): PhotoImage? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return PhotoImage(output.toByteArray(), image.width, image.height)
    }
}
