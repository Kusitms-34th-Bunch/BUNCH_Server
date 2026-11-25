package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the tests of the preparation of a day share: photos made by hand, and image fingerprints that tell scenes
 * apart. Two fingerprints of the same scene differ in a few bits, and the fingerprints of two scenes in about half of
 * them, so the alikeness of two photos is known without an image.
 */
internal object PreparationTestSupport {
    // Pairwise 32 bits apart: four scenes that are not alike.
    private val scenes = longArrayOf(
        0x0F0F0F0F0F0F0F0FL,
        0x3333333333333333L,
        0x5555555555555555L,
        0x00FF00FF00FF00FFL
    )

    /** The fingerprint of a scene, with the lowest [bitsApart] bits turned: another shot of it, a bit different. */
    fun scene(index: Int, bitsApart: Int = 0): Long = scenes[index] xor ((1L shl bitsApart) - 1)

    fun photo(
        id: Long,
        date: String? = "2026-10-19",
        time: String? = null,
        members: List<Long> = listOf(1L),
        fingerprint: Long? = null,
        offset: ZoneOffset? = null,
        aiStatus: AiStatus = AiStatus.DONE,
        analysis: AiAnalysis = AiAnalysis.NONE,
        batch: UUID? = null,
        memberUploadedAt: Map<Long, Instant> = emptyMap(),
        millis: Int? = null,
        model: String? = null,
        size: Pair<Int, Int>? = null
    ): RecoPhoto = RecoPhoto(
        photoId = id,
        capturedDate = date?.let(LocalDate::parse),
        capturedTime = time?.let(LocalTime::parse),
        utcOffset = offset,
        uploads = members.map {
            RecoPhoto.Upload(
                memberId = it,
                uploadBatchId = batch ?: UUID.nameUUIDFromBytes("batch-$id-$it".toByteArray()),
                uploadedAt = memberUploadedAt[it] ?: Instant.parse("2030-01-01T00:00:00Z").plusSeconds(id)
            )
        },
        uploadedAt = Instant.parse("2030-01-01T00:00:00Z").plusSeconds(id),
        source = SourceType.CAMERA,
        quality = PhotoQuality.GOOD,
        aiStatus = aiStatus,
        analysis = analysis,
        fingerprint = fingerprint,
        capturedMillis = millis,
        deviceModel = model,
        width = size?.first,
        height = size?.second
    )
}
