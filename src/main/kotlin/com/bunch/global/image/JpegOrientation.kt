package com.bunch.global.image

/** Reads the EXIF orientation from a JPEG without decoding its pixel data. */
public object JpegOrientation {
    private const val MARKER_PREFIX = 0xff
    private const val SOI = 0xd8
    private const val EOI = 0xd9
    private const val SOS = 0xda
    private const val APP1 = 0xe1
    private const val ORIENTATION_TAG = 0x0112
    private const val SHORT = 3
    private const val ENTRY_SIZE = 12

    @JvmStatic
    public fun read(bytes: ByteArray): Int {
        require(isJpeg(bytes)) { "JPEG input is required" }

        var position = 2
        var scans = 0
        while (position < bytes.size) {
            require(unsigned(bytes, position) == MARKER_PREFIX)
            while (position < bytes.size && unsigned(bytes, position) == MARKER_PREFIX) position++
            require(position < bytes.size)

            val marker = unsigned(bytes, position++)
            require(marker != 0 && marker != SOI)
            when {
                marker == EOI -> {
                    require(scans > 0 && position == bytes.size) { "JPEG is truncated or has trailing data" }
                    return 1
                }
                marker == 0x01 || marker in 0xd0..0xd7 -> continue
            }

            require(position + 2 <= bytes.size)
            val length = (unsigned(bytes, position) shl 8) or unsigned(bytes, position + 1)
            require(length >= 2 && position + length <= bytes.size)
            val payloadStart = position + 2
            val payloadEnd = position + length
            if (marker == APP1) {
                val orientation = readExifOrientation(bytes, payloadStart, payloadEnd)
                if (orientation != null) return orientation
            }
            position = payloadEnd
            if (marker == SOS) {
                scans++
                position = nextJpegMarker(bytes, position)
            }
        }
        throw IllegalArgumentException("JPEG has no complete end marker")
    }

    private fun readExifOrientation(bytes: ByteArray, start: Int, end: Int): Int? {
        if (end - start < 6 || !hasExifPrefix(bytes, start, end)) return null

        val tiff = start + 6
        require(tiff + 8 <= end)
        val littleEndian = when {
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> true
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> false
            else -> throw IllegalArgumentException("Invalid EXIF byte order")
        }

        fun u16(offset: Int): Int {
            require(offset >= tiff && offset + 2 <= end)
            val first = unsigned(bytes, offset)
            val second = unsigned(bytes, offset + 1)
            return if (littleEndian) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
        }

        fun u32(offset: Int): Long {
            val first = u16(offset).toLong()
            val second = u16(offset + 2).toLong()
            return if (littleEndian) {
                first or (second shl 16)
            } else {
                (first shl 16) or second
            }
        }

        require(u16(tiff + 2) == 42)
        val ifdOffset = u32(tiff + 4)
        require(ifdOffset <= Int.MAX_VALUE)
        val ifdLong = tiff.toLong() + ifdOffset
        require(ifdLong <= Int.MAX_VALUE)
        val ifd = ifdLong.toInt()
        require(ifd >= tiff && ifd + 2 <= end)
        val entryCount = u16(ifd)
        val entriesEnd = ifd.toLong() + 2L + entryCount.toLong() * ENTRY_SIZE
        require(entriesEnd <= end.toLong())

        for (index in 0 until entryCount) {
            val entry = ifd + 2 + index * ENTRY_SIZE
            if (u16(entry) != ORIENTATION_TAG) continue
            require(u16(entry + 2) == SHORT)
            require(u32(entry + 4) == 1L)
            val orientation = u16(entry + 8)
            require(orientation in 1..8)
            return orientation
        }
        return null
    }

    private fun hasExifPrefix(bytes: ByteArray, start: Int, end: Int): Boolean {
        val prefix = byteArrayOf(
            'E'.code.toByte(),
            'x'.code.toByte(),
            'i'.code.toByte(),
            'f'.code.toByte(),
            0,
            0
        )
        return end - start >= prefix.size && prefix.indices.all { bytes[start + it] == prefix[it] }
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && unsigned(bytes, 0) == MARKER_PREFIX && unsigned(bytes, 1) == SOI

    private fun nextJpegMarker(bytes: ByteArray, from: Int): Int {
        var position = from
        while (position < bytes.size - 1) {
            if (unsigned(bytes, position) != MARKER_PREFIX) {
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

    private fun unsigned(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff
}
