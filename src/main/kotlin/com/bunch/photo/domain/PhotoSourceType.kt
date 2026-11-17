package com.bunch.photo.domain

/**
 * Where the file of a photo comes from (recommended bundle specification 3-6, 4-4). Only a photo taken with the camera
 * can be used for a recommended bundle. The app also sends UNKNOWN when it cannot tell; that is not kept, because a
 * photo of an unknown origin has no origin.
 */
enum class PhotoSourceType {
    CAMERA,
    SCREENSHOT,
    SAVED
}
