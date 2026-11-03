package com.bunch.global.image

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode

/** A photo that passed [PhotoImageStorage.inspect]. The bytes are exactly what the client sent. */
class PhotoImage(val bytes: ByteArray, val width: Int, val height: Int)

enum class PhotoImageRejection {
    UNSUPPORTED_FORMAT,
    TOO_LARGE,
    RESOLUTION_EXCEEDED,
    LOCATION_METADATA
}

/** The client sent an image the photo box does not accept. Retrying the same file cannot succeed. */
class PhotoImageRejectedException(val reason: PhotoImageRejection) : BusinessException(ErrorCode.INVALID_INPUT)
