package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.IOException
import java.util.UUID

@Service
class PhotoImageStorage(
    private val objects: R2ObjectStorage = R2ObjectStorage(),
    @Value("\${photo-image.max-dimension:4096}")
    private val maxDimension: Int = 4096
) {
    /**
     * Checks a client upload without decoding it. The bytes are returned untouched so the caller can hash
     * them once and decide whether to [upload] at all.
     */
    fun inspect(file: MultipartFile): PhotoImage {
        if (!file.contentType.equals(MediaType.IMAGE_JPEG_VALUE, ignoreCase = true)) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        if (file.size > MAX_BYTES) reject(PhotoImageRejection.TOO_LARGE)
        val bytes = try {
            file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        } catch (_: IOException) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        if (bytes.size > MAX_BYTES) reject(PhotoImageRejection.TOO_LARGE)
        if (bytes.size < 4 || bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        val header = try {
            JpegHeaderInspector.inspect(bytes)
        } catch (_: RuntimeException) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        if (maxOf(header.width, header.height) > maxDimension) reject(PhotoImageRejection.RESOLUTION_EXCEEDED)
        if (header.hasLocationMetadata) reject(PhotoImageRejection.LOCATION_METADATA)
        val orientation = try {
            JpegOrientation.read(bytes)
        } catch (_: IllegalArgumentException) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        return if (orientation >= 5) {
            PhotoImage(bytes, header.height, header.width)
        } else {
            PhotoImage(bytes, header.width, header.height)
        }
    }

    fun upload(image: PhotoImage): StoredImage {
        // Validate/decode before any remote write; the original is never re-encoded.
        val thumbnail = try {
            PhotoThumbnail.create(image.bytes)
        } catch (_: Exception) {
            reject(PhotoImageRejection.UNSUPPORTED_FORMAT)
        }
        val key = "photos/${UUID.randomUUID()}/original.jpg"
        try {
            objects.put(key, image.bytes)
            objects.put(ImageObjectKeys.thumbnail(key), thumbnail)
            return StoredImage(key)
        } catch (_: Exception) {
            // PUT may have succeeded before its response was lost. Attempt both objects independently.
            objects.deleteImage(key)
            throw BusinessException(ErrorCode.PHOTO_IMAGE_STORAGE_FAILED)
        }
    }

    fun delete(objectKey: String?) = objects.deleteImage(objectKey)

    private fun reject(reason: PhotoImageRejection): Nothing = throw PhotoImageRejectedException(reason)

    private companion object {
        const val MAX_BYTES = 30 * 1024 * 1024
    }
}
