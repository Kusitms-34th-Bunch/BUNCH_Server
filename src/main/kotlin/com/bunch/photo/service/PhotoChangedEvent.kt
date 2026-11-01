package com.bunch.photo.service

import java.time.LocalDate

/**
 * Published inside the upload transaction when a photo or an uploader is added.
 * [photoAdded] is false when only an uploader joined an existing photo: the uploader count changes
 * but no new photo arrived, so the "last photo added" time of a recommendation must not move.
 * Listeners that must see committed data use `@TransactionalEventListener(AFTER_COMMIT)`.
 */
data class PhotoChangedEvent(val bunchId: Long, val capturedDate: LocalDate?, val photoAdded: Boolean)
