package com.bunch.global.image

import java.nio.charset.StandardCharsets.ISO_8859_1

internal data class JpegHeader(val width: Int, val height: Int, val hasLocationMetadata: Boolean)

/**
 * Reads only the JPEG marker segments before the scan data. The pixels are never decoded,
 * so a small file that declares a huge raster cannot make the server allocate that raster.
 * Malformed structure throws [IllegalArgumentException] instead of being guessed at.
 */
internal object JpegHeaderInspector {
    private const val MARKER_PREFIX = 0xff
    private const val SOI = 0xd8
    private const val EOI = 0xd9
    private const val SOS = 0xda
    private const val APP1 = 0xe1
    private const val GPS_IFD_POINTER = 0x8825
    private const val IFD_ENTRY_BYTES = 12
    private val EXIF_PREFIX = "Exif".toByteArray(ISO_8859_1) + byteArrayOf(0, 0)
    private val XMP_PREFIX = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(ISO_8859_1)

    // Property names that make an XMP packet a location carrier. IPTC (APP13) is not inspected.
    private val XMP_LOCATION_KEYS = listOf(
        "exif:GPS",
        "Iptc4xmpCore:Location",
        "Iptc4xmpExt:LocationCreated",
        "Iptc4xmpExt:LocationShown",
        "photoshop:City",
        "photoshop:State",
        "photoshop:Country"
    )

    fun inspect(bytes: ByteArray): JpegHeader {
        require(bytes.size >= 4 && unsigned(bytes, 0) == MARKER_PREFIX && unsigned(bytes, 1) == SOI)
        var position = 2
        var width = 0
        var height = 0
        var location = false
        while (true) {
            require(position < bytes.size && unsigned(bytes, position) == MARKER_PREFIX)
            while (position < bytes.size && unsigned(bytes, position) == MARKER_PREFIX) position++
            require(position < bytes.size)
            val marker = unsigned(bytes, position++)
            require(marker != EOI && marker != 0 && marker != SOI)
            if (marker == SOS) break
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            require(position + 2 <= bytes.size)
            val length = unsigned(bytes, position) shl 8 or unsigned(bytes, position + 1)
            require(length >= 2 && position + length <= bytes.size)
            val start = position + 2
            val end = position + length
            when {
                marker == APP1 -> location = location || carriesLocation(bytes, start, end)
                isFrameHeader(marker) -> {
                    require(length >= 8)
                    height = unsigned(bytes, start + 1) shl 8 or unsigned(bytes, start + 2)
                    width = unsigned(bytes, start + 3) shl 8 or unsigned(bytes, start + 4)
                }
            }
            position = end
        }
        require(width > 0 && height > 0)
        return JpegHeader(width, height, location)
    }

    // SOF0..SOF15 except DHT (c4), JPG (c8) and DAC (cc).
    private fun isFrameHeader(marker: Int) = marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)

    private fun carriesLocation(bytes: ByteArray, start: Int, end: Int): Boolean = when {
        startsWith(bytes, start, end, EXIF_PREFIX) -> exifHasGpsPointer(bytes, start + EXIF_PREFIX.size, end)
        startsWith(bytes, start, end, XMP_PREFIX) -> {
            val packet = String(bytes, start + XMP_PREFIX.size, end - start - XMP_PREFIX.size, ISO_8859_1)
            XMP_LOCATION_KEYS.any { packet.contains(it) }
        }
        else -> false
    }

    private fun exifHasGpsPointer(bytes: ByteArray, tiff: Int, end: Int): Boolean {
        require(end - tiff >= 8)
        val little = when {
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> true
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> false
            else -> throw IllegalArgumentException()
        }

        fun u16(offset: Int): Int {
            val first = unsigned(bytes, offset)
            val second = unsigned(bytes, offset + 1)
            return if (little) second shl 8 or first else first shl 8 or second
        }

        fun u32(offset: Int): Long {
            val low = u16(if (little) offset else offset + 2).toLong()
            val high = u16(if (little) offset + 2 else offset).toLong()
            return high shl 16 or low
        }
        require(u16(tiff + 2) == 42)
        val ifdOffset = u32(tiff + 4)
        require(ifdOffset >= 8 && tiff + ifdOffset + 2 <= end)
        val ifd = tiff + ifdOffset.toInt()
        val count = u16(ifd)
        require(ifd + 2 + count.toLong() * IFD_ENTRY_BYTES <= end)
        return (0 until count).any { u16(ifd + 2 + it * IFD_ENTRY_BYTES) == GPS_IFD_POINTER }
    }

    private fun startsWith(bytes: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean =
        end - start >= prefix.size && prefix.indices.all { bytes[start + it] == prefix[it] }

    private fun unsigned(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xff
}
