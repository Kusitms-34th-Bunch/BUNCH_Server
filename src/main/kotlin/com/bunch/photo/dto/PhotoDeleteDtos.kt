package com.bunch.photo.dto

data class PhotoDeleteRequest(val photoIds: List<Long>)

/**
 * [deletedCount] photos are gone from the photo box; this is the N of the "N photos deleted" toast.
 * [uploaderRemovedCount] jointly uploaded photos stay and only the caller's badge was removed.
 */
data class PhotoDeleteResponse(val deletedCount: Int, val uploaderRemovedCount: Int)
