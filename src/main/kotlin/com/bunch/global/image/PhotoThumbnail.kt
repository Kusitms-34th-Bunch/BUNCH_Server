package com.bunch.global.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Creates the private, metadata-free thumbnail stored beside a photo original. */
public object PhotoThumbnail {
    private const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    private const val MAX_DIMENSION = 4096
    private const val MAX_SIDE = 1080
    private const val JPEG_QUALITY = 0.85f
    private const val JPEG_MARKER = 0xff
    private const val JPEG_SOI = 0xd8
    private const val JPEG_EOI = 0xd9

    /**
     * Returns a central square JPEG no larger than 1080x1080, without enlarging a small source image.
     *
     * The input is decoded only while holding the process-wide [ImageDecodeLimiter] permit. Invalid,
     * truncated, warning-producing, oversized, or over-dimensioned images are rejected with
     * [IllegalArgumentException].
     */
    @JvmStatic
    public fun create(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "Image bytes must not be empty" }
        require(bytes.size <= MAX_INPUT_BYTES) { "Image is larger than 30 MiB" }
        return try {
            ImageDecodeLimiter.withPermit { decode(bytes) }
        } catch (invalid: IllegalArgumentException) {
            throw invalid
        } catch (io: IOException) {
            throw IllegalArgumentException("Image could not be decoded", io)
        } catch (failure: RuntimeException) {
            throw IllegalArgumentException("Image could not be decoded", failure)
        }
    }

    private fun decode(bytes: ByteArray): ByteArray {
        val orientation = JpegOrientation.read(bytes)
        requireJpegTerminated(bytes)

        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw IllegalArgumentException("Image input could not be created")
        input.use { imageInput ->
            val reader = ImageIO.getImageReaders(imageInput).asSequence().firstOrNull()
                ?: throw IllegalArgumentException("Unsupported image format")
            try {
                require(reader.formatName.equals("JPEG", ignoreCase = true)) { "JPEG input is required" }
                var warning = false
                reader.addIIOReadWarningListener { _, _ -> warning = true }
                reader.setInput(imageInput, false, false)

                // getWidth/getHeight only inspect the image header. Keep this check before any pixel read.
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
                    "Image dimensions exceed 4096px"
                }
                require(!warning) { "Image decoder reported a warning" }
                require(reader.getNumImages(true) == 1) { "Image must contain exactly one frame" }
                require(!warning) { "Image decoder reported a warning" }

                val sourceSquare = minOf(width, height)
                val targetSide = minOf(MAX_SIDE, sourceSquare)
                // Read every scan at or above the final square size so truncation outside the crop is still
                // observed by ImageIO. The full sampled raster remains bounded by the 4096px header limit.
                val subsampling = maxOf(1, sourceSquare / targetSide)
                val param = reader.defaultReadParam.apply {
                    setSourceSubsampling(subsampling, subsampling, 0, 0)
                }
                val decoded = reader.read(0, param)
                    ?: throw IllegalArgumentException("Image decoder returned no pixels")
                require(!warning) { "Image decoder reported a warning" }
                require(decoded.width in 1..MAX_DIMENSION && decoded.height in 1..MAX_DIMENSION) {
                    "Decoded image dimensions are invalid"
                }

                val square = centralSquare(decoded)
                val oriented = orient(square, orientation)
                val outputSide = minOf(targetSide, oriented.width, oriented.height)
                val output = resize(oriented, outputSide)
                return encodeJpeg(output)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun requireJpegTerminated(bytes: ByteArray) {
        if (bytes.size < 2 || (bytes[0].toInt() and 0xff) != JPEG_MARKER ||
            (bytes[1].toInt() and 0xff) != JPEG_SOI
        ) {
            return
        }

        var position = 2
        var scans = 0
        while (position < bytes.size) {
            require(unsigned(bytes, position) == JPEG_MARKER) { "Invalid JPEG marker" }
            while (position < bytes.size && unsigned(bytes, position) == JPEG_MARKER) position++
            require(position < bytes.size) { "Truncated JPEG marker" }
            val marker = unsigned(bytes, position++)
            require(marker != 0 && marker != JPEG_SOI) { "Invalid JPEG marker" }
            if (marker == JPEG_EOI) {
                require(scans > 0 && position == bytes.size) { "JPEG is truncated or has trailing data" }
                return
            }
            if (marker == 0x01 || marker in 0xd0..0xd7) continue

            require(position + 2 <= bytes.size) { "Truncated JPEG segment" }
            val length = (unsigned(bytes, position) shl 8) or unsigned(bytes, position + 1)
            require(length >= 2 && position + length <= bytes.size) { "Truncated JPEG segment" }
            position += length
            if (marker == 0xda) {
                scans++
                position = nextJpegMarker(bytes, position)
            }
        }
        throw IllegalArgumentException("JPEG has no complete end marker")
    }

    private fun nextJpegMarker(bytes: ByteArray, from: Int): Int {
        var position = from
        while (position < bytes.size - 1) {
            if (unsigned(bytes, position) != JPEG_MARKER) {
                position++
                continue
            }
            val next = unsigned(bytes, position + 1)
            if (next == 0 || next in 0xd0..0xd7) {
                position += 2
                continue
            }
            return position
        }
        throw IllegalArgumentException("Truncated JPEG scan")
    }

    private fun centralSquare(source: BufferedImage): BufferedImage {
        val side = minOf(source.width, source.height)
        require(side > 0)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        if (x == 0 && y == 0 && source.width == side && source.height == side) return source

        val result = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val pixels = source.getRGB(x, y, side, side, null, 0, side)
        result.setRGB(0, 0, side, side, pixels, 0, side)
        return result
    }

    private fun orient(source: BufferedImage, orientation: Int): BufferedImage {
        require(orientation in 1..8)
        val destinationWidth = if (orientation >= 5) source.height else source.width
        val destinationHeight = if (orientation >= 5) source.width else source.height
        val destination = BufferedImage(destinationWidth, destinationHeight, BufferedImage.TYPE_INT_RGB)

        for (y in 0 until destinationHeight) {
            for (x in 0 until destinationWidth) {
                val sourceX: Int
                val sourceY: Int
                when (orientation) {
                    1 -> {
                        sourceX = x
                        sourceY = y
                    }
                    2 -> {
                        sourceX = source.width - 1 - x
                        sourceY = y
                    }
                    3 -> {
                        sourceX = source.width - 1 - x
                        sourceY = source.height - 1 - y
                    }
                    4 -> {
                        sourceX = x
                        sourceY = source.height - 1 - y
                    }
                    5 -> {
                        sourceX = y
                        sourceY = x
                    }
                    6 -> {
                        sourceX = y
                        sourceY = source.height - 1 - x
                    }
                    7 -> {
                        sourceX = source.width - 1 - y
                        sourceY = source.height - 1 - x
                    }
                    8 -> {
                        sourceX = source.width - 1 - y
                        sourceY = x
                    }
                    else -> error("Unreachable EXIF orientation")
                }
                destination.setRGB(x, y, opaqueRgb(source.getRGB(sourceX, sourceY)))
            }
        }
        return destination
    }

    private fun resize(source: BufferedImage, side: Int): BufferedImage {
        require(side > 0)
        if (source.width == side && source.height == side) return source
        val result = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, side, side, null)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
            ?: throw IllegalStateException("No JPEG writer is available")
        val output = ByteArrayOutputStream()
        try {
            val imageOutput = ImageIO.createImageOutputStream(output)
                ?: throw IllegalStateException("JPEG output could not be created")
            imageOutput.use { stream ->
                writer.output = stream
                val parameter = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }
                writer.write(null, IIOImage(image, null, null), parameter)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun opaqueRgb(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xff
        if (alpha == 0xff) return argb
        val inverse = 0xff - alpha
        val red = (((argb ushr 16) and 0xff) * alpha + 0xff * inverse + 127) / 0xff
        val green = (((argb ushr 8) and 0xff) * alpha + 0xff * inverse + 127) / 0xff
        val blue = ((argb and 0xff) * alpha + 0xff * inverse + 127) / 0xff
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun unsigned(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff
}
