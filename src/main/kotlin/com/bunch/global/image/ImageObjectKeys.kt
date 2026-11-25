package com.bunch.global.image

/** Storage layout is shared by uploads and the one-time migration; arbitrary URLs are never accepted. */
object ImageObjectKeys {
    private val image = Regex("(?:photos/[A-Za-z0-9_-]+/(?:original|thumbnail)|profiles/[A-Za-z0-9_-]+/profile)\\.jpg")

    fun requireImage(key: String) {
        require(image.matches(key)) { "Invalid image object key" }
    }

    fun thumbnail(key: String): String {
        requireImage(key)
        require(key.startsWith("photos/") && key.endsWith("/original.jpg"))
        return key.removeSuffix("/original.jpg") + "/thumbnail.jpg"
    }
}
