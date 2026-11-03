package com.bunch.global.image

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.ISO_8859_1
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

internal data class JpegHeader(val width: Int, val height: Int, val hasLocationMetadata: Boolean)

/**
 * Reads the JPEG marker segments up to the first EOI, including the ones between scans and
 * after the last scan. Scan data is skipped byte by byte and the pixels are never decoded,
 * so a small file that declares a huge raster cannot make the server allocate that raster.
 * Bytes after the first EOI are not inspected.
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

    // XMP properties are matched by namespace URI and local name, never by the prefix a writer chose.
    // IPTC (APP13) is not inspected.
    private val XMP_GPS_NAMESPACES = setOf("http://ns.adobe.com/exif/1.0/", "http://cipa.jp/exif/1.0/")
    private val XMP_LOCATION_PROPERTIES = mapOf(
        "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/" to setOf("Location"),
        "http://iptc.org/std/Iptc4xmpExt/2008-02-29/" to setOf("LocationCreated", "LocationShown"),
        "http://ns.adobe.com/photoshop/1.0/" to setOf("City", "State", "Country")
    )

    fun inspect(bytes: ByteArray): JpegHeader {
        require(bytes.size >= 4 && unsigned(bytes, 0) == MARKER_PREFIX && unsigned(bytes, 1) == SOI)
        var position = 2
        var width = 0
        var height = 0
        var location = false
        var scans = 0
        while (true) {
            require(position < bytes.size && unsigned(bytes, position) == MARKER_PREFIX)
            while (position < bytes.size && unsigned(bytes, position) == MARKER_PREFIX) position++
            require(position < bytes.size)
            val marker = unsigned(bytes, position++)
            require(marker != 0 && marker != SOI)
            if (marker == EOI) {
                require(scans > 0)
                break
            }
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
            if (marker == SOS) {
                scans++
                position = skipScanData(bytes, position)
            }
        }
        require(width > 0 && height > 0)
        return JpegHeader(width, height, location)
    }

    // Entropy-coded data holds no markers except restart markers (ffd0..ffd7) and stuffed ff00 bytes.
    // Returns the index of the next real marker prefix.
    private fun skipScanData(bytes: ByteArray, from: Int): Int {
        var index = from
        while (index < bytes.size - 1) {
            if (unsigned(bytes, index) != MARKER_PREFIX) {
                index++
                continue
            }
            val next = unsigned(bytes, index + 1)
            if (next != 0 && next !in 0xd0..0xd7) return index
            index += 2
        }
        throw IllegalArgumentException()
    }

    // SOF0..SOF15 except DHT (c4), JPG (c8) and DAC (cc).
    private fun isFrameHeader(marker: Int) = marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)

    private fun carriesLocation(bytes: ByteArray, start: Int, end: Int): Boolean = when {
        startsWith(bytes, start, end, EXIF_PREFIX) -> exifHasGpsPointer(bytes, start + EXIF_PREFIX.size, end)
        startsWith(bytes, start, end, XMP_PREFIX) -> xmpHasLocation(bytes, start + XMP_PREFIX.size, end)
        else -> false
    }

    // The packet must be well-formed XML. DOCTYPE is refused so no entity or external resource is resolved.
    private fun xmpHasLocation(bytes: ByteArray, start: Int, end: Int): Boolean {
        var packetEnd = end
        while (packetEnd > start && bytes[packetEnd - 1].toInt() == 0) packetEnd--
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val builder = factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
            builder.parse(ByteArrayInputStream(bytes, start, packetEnd - start)).documentElement
        } catch (e: SAXException) {
            throw IllegalArgumentException(e)
        } catch (e: IOException) {
            throw IllegalArgumentException(e)
        } catch (e: ParserConfigurationException) {
            throw IllegalStateException(e)
        }

        // Iterative on purpose: a 64 KB packet can nest tens of thousands of elements.
        val pending = ArrayDeque<Element>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val element = pending.removeLast()
            if (isLocationProperty(element.namespaceURI, element.localName)) return true
            val attributes = element.attributes
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index)
                if (isLocationProperty(attribute.namespaceURI, attribute.localName)) return true
            }
            var child: Node? = element.firstChild
            while (child != null) {
                if (child is Element) pending.add(child)
                child = child.nextSibling
            }
        }
        return false
    }

    private fun isLocationProperty(namespace: String?, name: String?): Boolean = when {
        namespace == null || name == null -> false
        namespace in XMP_GPS_NAMESPACES -> name.startsWith("GPS")
        else -> name in XMP_LOCATION_PROPERTIES[namespace].orEmpty()
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
