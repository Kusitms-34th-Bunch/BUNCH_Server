
package com.bunch.global.image

/**
 * Builds the display URLs of a stored photo. Nothing is stored for them: the thumbnail is a
 * transformation of the one stored image, and the full size is that image itself.
 */
object PhotoImageUrls {
    const val DEFAULT_THUMBNAIL_SIZE = 300
    private const val MAX_THUMBNAIL_SIZE = 2560
    private const val UPLOAD_SEGMENT = "/image/upload/"

    fun full(url: String): String = url

    /** A URL that cannot be transformed (no upload segment) is returned unchanged. */
    fun thumbnail(url: String, size: Int = DEFAULT_THUMBNAIL_SIZE): String {
        require(size in 1..MAX_THUMBNAIL_SIZE)
        val index = url.indexOf(UPLOAD_SEGMENT)
        if (index < 0) return url
        val insertAt = index + UPLOAD_SEGMENT.length
        return url.substring(0, insertAt) + "c_fill,w_$size,h_$size/" + url.substring(insertAt)
    }
}
