package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.DayPreparer
import com.bunch.recommendation.domain.combination.PreparationResult
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SensitiveClueCode
import com.bunch.recommendation.domain.combination.SourceType
import com.bunch.recommendation.domain.combination.TimeState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID

/** The days of a bunch before any bundle is made (recommended bundle spec 3-1, 6; scenarios X2 to X7, X12, C1, E25). */
class DayPreparerTest {
    private val preparer = DayPreparer()

    private fun prepare(vararg photos: RecoPhoto): PreparationResult = preparer.prepare(photos.toList())

    private fun day(result: PreparationResult, date: String): DayPreparation =
        result.days.single { it.dayKey == LocalDate.parse(date) }

    private fun ready(result: PreparationResult, date: String): DayPreparation.Ready =
        day(result, date) as DayPreparation.Ready

    private fun sensitive(confidence: Double) = AiAnalysis(
        sensitiveClues = listOf(Reading(SensitiveClueCode.FUNERAL_WREATH, confidence))
    )

    @Test
    fun `a bunch without photos has no day`() {
        val result = prepare()

        assertThat(result.days).isEmpty()
        assertThat(result.excludedPhotoIds).isEmpty()
    }

    @Test
    fun `the photos of a day are ready in the order of the time, then the photo ID`() {
        val result = prepare(
            photo(3, time = "12:00"),
            photo(1, time = "12:00"),
            photo(2, time = "09:00"),
            photo(4, time = null)
        )

        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(2L, 1L, 3L, 4L)
    }

    @Test
    fun `a day ends at five in the morning`() {
        val result = prepare(
            photo(1, date = "2026-10-20", time = "04:59"),
            photo(2, date = "2026-10-20", time = "05:00")
        )

        assertThat(result.days.map { it.dayKey })
            .containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20))
        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(1L)
        assertThat(ready(result, "2026-10-20").photos.map { it.photoId }).containsExactly(2L)
    }

    @Test
    fun `X2 a photo without a date is left out and no date is borrowed from a photo that looks the same`() {
        val result = prepare(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0, bitsApart = 1)),
            photo(3, date = null, fingerprint = null)
        )

        assertThat(result.excludedPhotoIds).containsExactly(2L, 3L)
        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(1L)
    }

    @Test
    fun `X3 a photo with a date only stays on its day without a time, and no time is borrowed`() {
        val result = prepare(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = "2026-10-19", fingerprint = scene(0)),
            photo(3, date = "2026-10-19", fingerprint = scene(2))
        )

        val photos = ready(result, "2026-10-19").photos.associateBy { it.photoId }
        assertThat(photos.getValue(1).timeState).isEqualTo(TimeState.FULL)
        assertThat(photos.getValue(2).timeState).isEqualTo(TimeState.DATE_ONLY)
        assertThat(photos.getValue(2).instant).isNull()
        assertThat(photos.getValue(3).timeState).isEqualTo(TimeState.DATE_ONLY)
        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(1L, 2L, 3L)
        assertThat(result.excludedPhotoIds).isEmpty()
    }

    @Test
    fun `X4 members in other time zones have the day by the local clock and the gap by the absolute one`() {
        // One member with one device shoots in two places: a burst on the absolute clock (R1), two seconds apart.
        val seoul = photo(1, time = "10:00:00", offset = ZoneOffset.ofHours(9), model = "iPhone15,2")
        val tokyoSide = photo(
            2,
            time = "09:00:02",
            offset = ZoneOffset.ofHours(8),
            model = "iPhone15,2"
        )
        val newYork = photo(3, date = "2026-10-18", time = "19:00:00", offset = ZoneOffset.ofHours(-5))

        val result = prepare(seoul, tokyoSide, newYork)

        val sameDay = ready(result, "2026-10-19")
        assertThat(sameDay.duplicateGroups.map { it.photoIds }).containsExactly(listOf(1L, 2L))
        assertThat(sameDay.photos.map { it.instant }).containsExactly(
            Instant.parse("2026-10-19T01:00:00Z"),
            Instant.parse("2026-10-19T01:00:02Z")
        )
        assertThat(ready(result, "2026-10-18").photos.single().instant)
            .isEqualTo(Instant.parse("2026-10-19T00:00:00Z"))
    }

    @Test
    fun `X5 an upload batch taken with a clock that is ahead of its upload is left out, the other photos stay`() {
        val uploadedAt = mapOf(1L to Instant.parse("2026-10-19T00:00:00Z"))
        val batch = UUID.nameUUIDFromBytes("wrong".toByteArray())
        // 14 hours and a second after the upload, without an offset: the clock is wrong, so is the whole batch.
        val result = prepare(
            photo(1, date = "2026-10-19", time = "23:00:01", batch = batch, memberUploadedAt = uploadedAt),
            photo(2, date = "2026-10-19", time = "09:00", batch = batch, memberUploadedAt = uploadedAt),
            photo(3, date = "2026-10-19", time = "10:00", members = listOf(2))
        )

        assertThat(result.excludedPhotoIds).containsExactly(1L, 2L)
        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(3L)
    }

    @Test
    fun `X5 a clock that is only slow is not corrected, the photos keep their own times`() {
        val result = prepare(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "09:30", members = listOf(2), fingerprint = scene(0))
        )

        val prepared = ready(result, "2026-10-19")
        assertThat(prepared.photos.map { it.photoId }).containsExactly(2L, 1L)
        assertThat(prepared.photos.map { it.localTime?.toLocalTime().toString() }).containsExactly("09:30", "10:00")
        assertThat(result.excludedPhotoIds).isEmpty()
    }

    @Test
    fun `X6 a day with a photo that is still analyzed waits`() {
        val result = prepare(
            photo(1, time = "10:00", aiStatus = AiStatus.DONE),
            photo(2, time = "10:10", aiStatus = AiStatus.PENDING),
            photo(3, date = "2026-10-20", time = "10:00", aiStatus = AiStatus.DONE)
        )

        assertThat(day(result, "2026-10-19")).isEqualTo(DayPreparation.Held(LocalDate.of(2026, 10, 19), listOf(2L)))
        assertThat(day(result, "2026-10-20")).isInstanceOf(DayPreparation.Ready::class.java)
    }

    @Test
    fun `a day that waits waits even when a photo of it shows a funeral`() {
        val result = prepare(
            photo(1, time = "10:00", aiStatus = AiStatus.PENDING),
            photo(2, time = "10:10", analysis = sensitive(0.9))
        )

        assertThat(day(result, "2026-10-19")).isInstanceOf(DayPreparation.Held::class.java)
    }

    @Test
    fun `X7 a photo whose analysis failed has no clue and does not hold the day`() {
        val result = prepare(photo(1, time = "10:00", aiStatus = AiStatus.FAILED, analysis = AiAnalysis.NONE))

        assertThat(day(result, "2026-10-19")).isInstanceOf(DayPreparation.Ready::class.java)
    }

    @Test
    fun `X12 a day with a sensitive clue is not recommended, and E25 a funeral stays out of the bundles`() {
        val result = prepare(
            photo(1, time = "10:00", analysis = sensitive(0.9)),
            photo(2, time = "10:10"),
            photo(3, date = "2026-10-20", time = "10:00")
        )

        val blocked = day(result, "2026-10-19") as DayPreparation.Blocked
        assertThat(blocked.findings.map { it.photoId }).containsExactly(1L)
        assertThat(blocked.findings.single().clue).isEqualTo(SensitiveClueCode.FUNERAL_WREATH)
        assertThat(day(result, "2026-10-20")).isInstanceOf(DayPreparation.Ready::class.java)
    }

    @Test
    fun `a sensitive clue counts from the confidence of 0_50`() {
        assertThat(day(prepare(photo(1, time = "10:00", analysis = sensitive(0.49))), "2026-10-19"))
            .isInstanceOf(DayPreparation.Ready::class.java)
        assertThat(day(prepare(photo(1, time = "10:00", analysis = sensitive(0.50))), "2026-10-19"))
            .isInstanceOf(DayPreparation.Blocked::class.java)
    }

    @Test
    fun `a sensitive clue in a photo that is not usable still stops the day`() {
        val screenshot = photo(1, time = "10:00", analysis = sensitive(0.8)).copy(source = SourceType.SCREENSHOT)

        val result = prepare(screenshot)

        assertThat(day(result, "2026-10-19")).isInstanceOf(DayPreparation.Blocked::class.java)
    }

    @Test
    fun `C1 the same photo uploaded by several members is one photo and all of them count`() {
        // The same original saved again by another member: the same moment to the millisecond, model and size (R1).
        val result = prepare(
            photo(1, time = "10:00:00", members = listOf(1, 2), millis = 120, model = "A", size = 4032 to 3024),
            photo(2, time = "10:00:00", members = listOf(3), millis = 120, model = "A", size = 4032 to 3024)
        )

        val group = ready(result, "2026-10-19").duplicateGroups.single()
        assertThat(group.photoIds).containsExactly(1L, 2L)
        assertThat(group.uploaderIds).containsExactlyInAnyOrder(1L, 2L, 3L)
    }

    @Test
    fun `the photos themselves are not changed`() {
        val dated = photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0))
        val dateOnly = photo(2, date = "2026-10-19", fingerprint = scene(0, bitsApart = 1))

        val result = prepare(dated, dateOnly)

        assertThat(ready(result, "2026-10-19").photos.map { it.photo }).containsExactlyInAnyOrder(dated, dateOnly)
    }

    @Test
    fun `the same photos give the same result whatever the order they come in`() {
        val photos = listOf(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0, bitsApart = 1)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1, bitsApart = 1)),
            photo(3, time = "09:30", members = listOf(2), fingerprint = scene(0)),
            photo(4, time = "09:40", members = listOf(2), fingerprint = scene(1)),
            photo(5, date = null, fingerprint = scene(0)),
            photo(6, date = "2026-10-20", time = "12:00", analysis = sensitive(0.7))
        )

        assertThat(preparer.prepare(photos.reversed())).isEqualTo(preparer.prepare(photos))
        assertThat(preparer.prepare(photos.shuffled(Random(7)))).isEqualTo(preparer.prepare(photos))
    }
}
