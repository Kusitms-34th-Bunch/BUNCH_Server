package com.bunch.recommendation.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDate
import java.time.LocalTime

/** The cards of the recommended bundle sheet, in the order of the list (4.2.1, 4.2.9 rule 6). */
data class RecommendedBundleListResponse(val bundles: List<RecommendedBundleCard>)

/**
 * One card (4.2.2). [photoCount] is the number of photos of the card. [endDate] is set only for a bundle that was
 * taken over midnight. [uploaders] are the members who uploaded the photos of the card, the one with the most
 * photos first. The app shortens them to 「name 외 N명」 by the width of the row. [coverImageUrl] is the first
 * photo of the card.
 *
 * [kind], [eventType] and [activity] are only given by the recommended bundles of the new combination: `EVENT` or
 * `GENERAL`, the kind of the event of an event bundle, and the main activity of a general bundle (the names of the
 * recommended bundle specification). They are left out when they are empty, so the answer of the old recommended
 * bundles is the same as it was, and the app may ignore them. The title is the finished text of the card.
 */
data class RecommendedBundleCard(
    val id: Long,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val photoCount: Int,
    val uploaders: List<RecommendedUploader>,
    val coverImageUrl: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val kind: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val eventType: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val activity: String? = null
)

/** The photos of the card in the order they are applied in (4.2.3), the same ones the detail screen shows (4.2.5). */
data class RecommendedBundleDetailResponse(
    val id: Long,
    val title: String,
    val photos: List<RecommendedBundlePhoto>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val kind: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val eventType: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val activity: String? = null
)

/** Every photo of a recommended bundle has a date (4.2.7), so [capturedDate] is never empty. */
data class RecommendedBundlePhoto(
    val photoId: Long,
    val thumbnailUrl: String,
    val imageUrl: String,
    val capturedDate: LocalDate,
    val capturedTime: LocalTime?,
    val uploaders: List<RecommendedUploader>
)

/** A member as the badge of the photo viewer shows them (4.2.6): the nickname and the profile image now. */
data class RecommendedUploader(
    val memberId: Long,
    val nickname: String?,
    val imageUrl: String?
)
