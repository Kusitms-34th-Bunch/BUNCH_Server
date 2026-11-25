package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

@Service
class ProfileImageStorage(private val objects: R2ObjectStorage = R2ObjectStorage()) {
    fun upload(file: MultipartFile): StoredImage = ImageUploadLimiter.withPermit {
        val image = sanitize(file)
        val key = "profiles/${UUID.randomUUID()}/profile.jpg"
        try {
            objects.put(key, image.bytes)
            StoredImage(key)
        } catch (_: Exception) {
            objects.deleteImage(key)
            throw BusinessException(ErrorCode.PROFILE_IMAGE_FAILED)
        }
    }

    // Asset release also uses this method for photos retained by published bundles.
    fun delete(objectKey: String?) = objects.deleteImage(objectKey)

    private fun sanitize(file: MultipartFile): SanitizedImage {
        try {
            check(file.contentType.equals(MediaType.IMAGE_JPEG_VALUE, ignoreCase = true))
            check(file.size in 1..MAX_BYTES.toLong())
            val bytes = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            check(bytes.size in 4..MAX_BYTES)
            check(
                bytes[0] == 0xff.toByte() &&
                    bytes[1] == 0xd8.toByte() &&
                    bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
                    bytes.last() == 0xd9.toByte()
            )
            return ImageDecodeLimiter.withPermit {
                MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    check(readers.hasNext())
                    val reader = readers.next()
                    try {
                        check(reader.formatName.equals("JPEG", ignoreCase = true))
                        reader.setInput(input, false, false)
                        var warning = false
                        reader.addIIOReadWarningListener { _, _ -> warning = true }
                        // Inspect SOF dimensions before getNumImages/read can process the image data.
                        val width = reader.getWidth(0)
                        val height = reader.getHeight(0)
                        check(width in 1..MAX_DIMENSION && height == width)
                        check(reader.getNumImages(true) == 1 && !warning)
                        val decoded = reader.read(0) ?: throw IllegalArgumentException()
                        check(!warning && decoded.width == width && decoded.height == height)
                        val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                        val graphics = rgb.createGraphics()
                        try {
                            graphics.drawImage(decoded, 0, 0, null)
                        } finally {
                            graphics.dispose()
                        }
                        val output = ByteArrayOutputStream()
                        // A new RGB image and default writer metadata discard EXIF, comments and profiles.
                        check(ImageIO.write(rgb, "jpeg", output))
                        return@withPermit SanitizedImage(output.toByteArray(), width)
                    } finally {
                        reader.dispose()
                    }
                }
            }
        } catch (_: Exception) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
    }

    private data class SanitizedImage(val bytes: ByteArray, val size: Int)

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val MAX_DIMENSION = 1024
    }
}
