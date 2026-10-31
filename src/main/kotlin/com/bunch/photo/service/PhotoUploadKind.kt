package com.bunch.photo.service

enum class PhotoUploadKind {
    /** A new photo was stored. */
    STORED,

    /** The same file was already in the bunch; this member was added as an uploader. */
    MERGED,

    /** The same file was already uploaded by this member. Nothing changed. */
    ALREADY_UPLOADED
}
