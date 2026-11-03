package com.bunch.photo.domain

/** An uploader's badge: the member's current nickname and profile image, which is null without one. */
interface PhotoUploaderProfile {
    val memberId: Long
    val nickname: String?
    val imageUrl: String?
}
