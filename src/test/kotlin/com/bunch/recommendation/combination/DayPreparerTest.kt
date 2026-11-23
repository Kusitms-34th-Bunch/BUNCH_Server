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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Random

/** The days of a bunch before any bundle is made (recommended bundle spec 5; scenarios X2 to X7, X12, C1, E25). */
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
        assertThat(result.movedDays).isEmpty()
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
    fun `X2 a photo without a date takes the date and the time of a photo that looks the same`() {
        val result = prepare(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(0, bitsApart = 1))
        )

        val borrowed = ready(result, "2026-10-19").photos.single { it.photoId == 2L }
        assertThat(borrowed.localTime?.toLocalTime()).isEqualTo(LocalTime.of(21, 10))
        assertThat(borrowed.borrowedFrom).isEqualTo(1L)
        assertThat(borrowed.timeState).isEqualTo(TimeState.FULL)
        assertThat(borrowed.photo.capturedDate).isNull()
        assertThat(result.excludedPhotoIds).isEmpty()
    }

    @Test
    fun `X2 a photo without a date that nothing looks like is left out`() {
        val result = prepare(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = null, fingerprint = scene(1)),
            photo(3, date = null, fingerprint = null)
        )

        assertThat(result.excludedPhotoIds).containsExactly(2L, 3L)
        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(1L)
    }

    @Test
    fun `X3 a photo with a date only takes the time of a photo that looks the same, or stays with its date`() {
        val result = prepare(
            photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0)),
            photo(2, date = "2026-10-19", fingerprint = scene(0)),
            photo(3, date = "2026-10-19", fingerprint = scene(2))
        )

        val photos = ready(result, "2026-10-19").photos.associateBy { it.photoId }
        assertThat(photos.getValue(2).timeState).isEqualTo(TimeState.FULL)
        assertThat(photos.getValue(2).borrowedFrom).isEqualTo(1L)
        assertThat(photos.getValue(3).timeState).isEqualTo(TimeState.DATE_ONLY)
        assertThat(photos.getValue(3).instant).isNull()
        assertThat(ready(result, "2026-10-19").photos.last().photoId).isEqualTo(3L)
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
    fun `X5 a clock that is slow is corrected before the day is prepared`() {
        val result = prepare(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0, bitsApart = 1)),
            photo(2, time = "10:10", members = listOf(1), fingerprint = scene(1, bitsApart = 1)),
            photo(3, time = "09:30", members = listOf(2), fingerprint = scene(0)),
            photo(4, time = "09:40", members = listOf(2), fingerprint = scene(1))
        )

        val prepared = ready(result, "2026-10-19")
        assertThat(prepared.photos.single { it.photoId == 3L }.clockShift).isEqualTo(Duration.ofMinutes(30))
        assertThat(prepared.photos.single { it.photoId == 3L }.photo.capturedTime).isEqualTo(LocalTime.of(9, 30))
        // The scenes are not looked at any more: the same scene of two members is not a duplicate group (R1).
        assertThat(prepared.duplicateGroups.map { it.photoIds })
            .containsExactly(listOf(1L), listOf(3L), listOf(2L), listOf(4L))
        assertThat(result.movedDays).isEmpty()
    }

    @Test
    fun `X5 a correction that takes a photo to another day tells both days`() {
        val result = prepare(
            photo(1, time = "04:10", members = listOf(1), fingerprint = scene(2)),
            photo(2, time = "04:50", members = listOf(1), fingerprint = scene(0, bitsApart = 1)),
            photo(3, time = "04:55", members = listOf(1), fingerprint = scene(1, bitsApart = 1)),
            photo(4, time = "04:20", members = listOf(2), fingerprint = scene(0)),
            photo(5, time = "04:25", members = listOf(2), fingerprint = scene(1)),
            photo(6, time = "04:45", members = listOf(2), fingerprint = scene(3))
        )

        assertThat(result.movedDays).containsExactlyInAnyOrder(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19))
        assertThat(ready(result, "2026-10-19").photos.map { it.photoId }).containsExactly(6L)
        assertThat(ready(result, "2026-10-18").photos.map { it.photoId }).doesNotContain(6L)
    }

    @Test
    fun `X5 with nothing to compare the clock is left as it is`() {
        val result = prepare(
            photo(1, time = "10:00", members = listOf(1), fingerprint = scene(0)),
            photo(2, time = "09:00", members = listOf(2), fingerprint = scene(1))
        )

        assertThat(ready(result, "2026-10-19").photos.map { it.clockShift }).containsOnly(Duration.ZERO)
        assertThat(result.movedDays).isEmpty()
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
        val original = photo(2, date = null, fingerprint = scene(0, bitsApart = 1))
        val lender = photo(1, date = "2026-10-19", time = "21:10", fingerprint = scene(0))

        val result = prepare(lender, original)

        assertThat(ready(result, "2026-10-19").photos.map { it.photo }).containsExactlyInAnyOrder(lender, original)
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
