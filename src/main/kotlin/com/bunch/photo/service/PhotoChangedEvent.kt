package com.bunch.photo.service

import java.time.LocalDate

/**
 * Published inside the transaction of a change to the photo box on [capturedDate]: a photo or an uploader was added
 * or removed, or a photo moved to or from the date (3.4.1).
 * [photoAdded] is true only when a new photo arrived. It is false when only an uploader joined an existing photo,
 * left a photo, or a photo was deleted or moved: the uploader count or the photos change, but no new photo
 * arrived, so the "last photo added" time of a recommendation must not move.
 * Listeners that must see committed data use `@TransactionalEventListener(AFTER_COMMIT)`.
 */
data class PhotoChangedEvent(val bunchId: Long, val capturedDate: LocalDate?, val photoAdded: Boolean)
