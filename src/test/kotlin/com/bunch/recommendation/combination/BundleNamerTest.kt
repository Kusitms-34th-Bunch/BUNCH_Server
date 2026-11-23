package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CombinerTestSupport.TODAY
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.BundleNames
import com.bunch.recommendation.domain.combination.BundleNamer
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DayPart
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The titles of the bundles of a day (recommended bundle specification 4, the name of a bundle). */
class BundleNamerTest {
    private val namer = BundleNamer()

    private fun bundle(day: DayPreparation.Ready, ids: List<Long>): RecoBundle {
        val inBundle = day.photos.filter { it.photoId in ids }
        val instants = inBundle.mapNotNull { it.instant }
        val from = instants.minOrNull() ?: Instant.parse("2026-10-18T20:00:00Z")
        return RecoBundle(
            dayKey = day.dayKey,
            kind = BundleKind.GENERAL,
            eventType = null,
            establishedBy = null,
            activity = null,
            subIndex = null,
            photos = inBundle.map { RecoBundle.BundlePhoto(it.photoId, PhotoRole.GENERAL) },
            coverPhotoId = null,
            startAt = from,
            endAt = instants.maxOrNull() ?: from,
            photoCount = ids.size,
            uploaderCount = 2,
            hasGroupPhoto = false,
            trace = RuleTrace(day.dayKey, BundleKind.GENERAL, RuleTrace.Counts(ids.size, 2), "reco-cfg-1")
        )
    }

    // The titles of the bundles that each group of photos makes, in the order of the groups.
    private fun titles(vararg groups: List<RecoPhoto>, today: LocalDate = TODAY): List<String> {
        val day = ready(groups.toList().flatten())
        val bundles = groups.map { group -> bundle(day, group.map { it.photoId }) }
        return namer.name(bundles, day, today).map { it.title!! }
    }

    private fun title(photos: List<RecoPhoto>, today: LocalDate = TODAY): String =
        titles(photos, today = today).single()

    // --- the date and the part of the day ---

    @Test
    fun `a bundle is the date and the part of the day, whatever the photos show`() {
        val result = titles(shots(1, "09:00", 3), shots(11, "14:00", 3), shots(21, "21:00", 3))

        assertThat(result).containsExactly("10월 19일 오전", "10월 19일 오후", "10월 19일 밤")
    }

    @Test
    fun `the year is in front when it is not the year of today`() {
        assertThat(title(shots(1, "20:00", 3, date = "2025-10-19"))).isEqualTo("2025년 10월 19일 밤")
        assertThat(title(shots(1, "20:00", 3, date = "2027-01-05"))).isEqualTo("2027년 1월 5일 밤")
        assertThat(title(shots(1, "20:00", 3, date = "2026-01-01"))).isEqualTo("1월 1일 밤")
        assertThat(title(shots(1, "20:00", 3, date = "2026-12-31"))).isEqualTo("12월 31일 밤")
    }

    @Test
    fun `the year is read from the day that is given, not from the clock`() {
        val photos = shots(1, "20:00", 3)

        assertThat(title(photos, LocalDate.parse("2026-01-01"))).isEqualTo("10월 19일 밤")
        assertThat(title(photos, LocalDate.parse("2027-01-01"))).isEqualTo("2026년 10월 19일 밤")
    }

    @Test
    fun `D7 a bundle that goes past midnight has the date it starts`() {
        val photos = shots(1, "23:30", 1) + shots(2, "00:10", 2, date = "2026-10-20")

        assertThat(title(photos)).isEqualTo("10월 19일 밤")
    }

    @Test
    fun `D7 a bundle that starts after midnight has the date of the calendar day, not of its day of 05 to 05`() {
        assertThat(title(shots(1, "01:00", 3, date = "2026-10-20"))).isEqualTo("10월 20일 밤")
        assertThat(title(shots(1, "04:30", 3, step = 5, date = "2026-10-20"))).isEqualTo("10월 20일 밤")
    }

    @Test
    fun `D7 the year is the one of the date it starts, and a bundle after midnight of a new year is of the new year`() {
        val newYear = shots(1, "00:30", 3, date = "2027-01-01")

        assertThat(title(newYear)).isEqualTo("2027년 1월 1일 밤")
    }

    @Test
    fun `the part of the day starts at its hour and ends before the next one`() {
        assertThat(title(shots(1, "05:00", 3))).isEqualTo("10월 19일 오전")
        assertThat(title(shots(1, "11:50", 3, step = 1))).isEqualTo("10월 19일 오전")
        assertThat(title(shots(1, "12:00", 3, step = 5))).isEqualTo("10월 19일 오후")
        assertThat(title(shots(1, "18:00", 3, step = 5))).isEqualTo("10월 19일 밤")
    }

    // --- 70 percent, and two parts (G6, G9) ---

    @Test
    fun `G6 a party from the afternoon to the night is the night when most of the photos are in it`() {
        assertThat(title(shots(1, "17:00", 25))).isEqualTo("10월 19일 밤")
    }

    @Test
    fun `G9 a cafe in the morning and a lunch in the afternoon with no part of 70 percent have both parts`() {
        val photos = shots(1, "10:00", 10) + shots(100, "13:00", 13)

        assertThat(title(photos)).isEqualTo("10월 19일 오전~오후")
    }

    @Test
    fun `G25 a morning cafe and an afternoon park with half of the photos each have both parts`() {
        val photos = shots(1, "09:00", 8) + shots(100, "12:40", 8, step = 5)

        assertThat(title(photos)).isEqualTo("10월 19일 오전~오후")
    }

    @Test
    fun `a part with exactly 70 percent of the photos names the bundle, and one with less does not`() {
        val seven = shots(1, "09:00", 7) + shots(20, "14:00", 3)
        val sixOfNine = shots(1, "09:00", 6) + shots(20, "14:00", 3)
        val justBelow = shots(1, "09:00", 9) + shots(20, "14:00", 4)
        val justAbove = shots(1, "09:00", 10) + shots(20, "14:00", 4)

        assertThat(title(seven)).isEqualTo("10월 19일 오전")
        assertThat(title(sixOfNine)).isEqualTo("10월 19일 오전~오후")
        assertThat(title(justBelow)).isEqualTo("10월 19일 오전~오후")
        assertThat(title(justAbove)).isEqualTo("10월 19일 오전")
    }

    @Test
    fun `the share is a setting`() {
        val photos = shots(1, "09:00", 6) + shots(20, "14:00", 4)

        val looser = BundleNamer(CombinationSettings(dayPartMainShare = 0.6))

        val day = ready(photos)
        val named = looser.name(listOf(bundle(day, photos.map { it.photoId })), day, TODAY)
        assertThat(named.single().title).isEqualTo("10월 19일 오전")
    }

    @Test
    fun `a share of a half or less is refused, because two parts could have it`() {
        assertThatThrownBy { CombinationSettings(dayPartMainShare = 0.5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CombinationSettings(dayPartMainShare = 1.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `of three parts the two with the most photos are put together in the order of the day`() {
        val photos = shots(1, "09:00", 4) + shots(10, "14:00", 3) + shots(20, "21:00", 3)
        val farApart = shots(1, "09:00", 5) + shots(10, "14:00", 1) + shots(20, "21:00", 4)

        assertThat(title(photos)).isEqualTo("10월 19일 오전~오후")
        assertThat(title(farApart)).isEqualTo("10월 19일 오전~밤")
    }

    @Test
    fun `when two parts have as many photos they are the two, and the earlier part comes first`() {
        assertThat(title(shots(1, "21:00", 5) + shots(10, "09:00", 5))).isEqualTo("10월 19일 오전~밤")
    }

    @Test
    fun `a photo with a date only does not vote for the part of the day`() {
        val dateOnly = (90L..92L).map { photo(it, time = null) }
        val photos = shots(1, "14:00", 3) + dateOnly

        assertThat(title(photos)).isEqualTo("10월 19일 오후")
    }

    @Test
    fun `a bundle with no photo that has a time has no part of the day in its title`() {
        assertThat(title(listOf(photo(1, time = null), photo(2, time = null)))).isEqualTo("10월 19일")
    }

    // --- two titles of a day that are the same: the time the bundle starts ---

    @Test
    fun `bundles with the same title have the time they start, and the others keep their title`() {
        val result = titles(shots(1, "13:20", 3), shots(10, "16:00", 3), shots(20, "21:00", 3))

        assertThat(result).containsExactly("10월 19일 오후 1시", "10월 19일 오후 4시", "10월 19일 밤")
    }

    @Test
    fun `three bundles with the same title all have the time they start`() {
        val result = titles(shots(1, "07:10", 3), shots(10, "09:00", 3), shots(20, "11:00", 3))

        assertThat(result).containsExactly("10월 19일 오전 7시", "10월 19일 오전 9시", "10월 19일 오전 11시")
    }

    @Test
    fun `an evening and a night after midnight of the same day are of two dates, so they have no time`() {
        val result = titles(shots(1, "21:00", 3), shots(10, "00:30", 3, date = "2026-10-20"))

        assertThat(result).containsExactly("10월 19일 밤", "10월 20일 밤")
    }

    @Test
    fun `the hour is on a clock of 12 hours with the part of the day of the first photo`() {
        val noon = titles(shots(1, "12:30", 3), shots(10, "15:00", 3))
        val night = titles(shots(1, "21:30", 3), shots(10, "23:00", 3))
        val pastMidnight = titles(
            shots(1, "00:30", 3, date = "2026-10-20"),
            shots(10, "02:30", 3, date = "2026-10-20")
        )

        assertThat(noon).containsExactly("10월 19일 오후 12시", "10월 19일 오후 3시")
        assertThat(night).containsExactly("10월 19일 밤 9시", "10월 19일 밤 11시")
        assertThat(pastMidnight).containsExactly("10월 20일 밤 12시", "10월 20일 밤 2시")
    }

    @Test
    fun `bundles that start in the same hour have the minute too`() {
        val result = titles(shots(1, "14:05", 3), shots(10, "14:40", 3))

        assertThat(result).containsExactly("10월 19일 오후 2시 5분", "10월 19일 오후 2시 40분")
    }

    @Test
    fun `the one that starts at the hour has no minute, so that it differs from the one that starts later in it`() {
        val result = titles(shots(1, "14:00", 3), shots(10, "14:40", 3))

        assertThat(result).containsExactly("10월 19일 오후 2시", "10월 19일 오후 2시 40분")
    }

    @Test
    fun `the year is in front of a title with the time too`() {
        val result = titles(shots(1, "13:00", 3, date = "2025-10-19"), shots(10, "16:00", 3, date = "2025-10-19"))

        assertThat(result).containsExactly("2025년 10월 19일 오후 1시", "2025년 10월 19일 오후 4시")
    }

    @Test
    fun `a title is the same whatever the order the bundles are given in`() {
        val first = shots(1, "13:20", 3)
        val second = shots(10, "16:00", 3)
        val third = shots(20, "21:00", 3)

        val straight = titles(first, second, third)
        val reversed = titles(third, second, first)

        assertThat(reversed).containsExactlyElementsOf(straight.reversed())
    }

    @Test
    fun `only the title is given, the bundles come back in the order they were given`() {
        val photos = shots(1, "09:00", 3) + shots(11, "14:00", 3)
        val day = ready(photos)
        val late = bundle(day, listOf(11, 12, 13))
        val early = bundle(day, listOf(1, 2, 3))

        val result = namer.name(listOf(late, early), day, TODAY)

        assertThat(result.map { it.title }).containsExactly("10월 19일 오후", "10월 19일 오전")
        assertThat(result.map { it.copy(title = null) }).containsExactly(late, early)
    }

    @Test
    fun `no bundle gives no title`() {
        val day = ready(shots(1, "09:00", 3))

        assertThat(namer.name(emptyList(), day, TODAY)).isEmpty()
    }

    @Test
    fun `the parts of the day have the names of the specification`() {
        assertThat(BundleNames.dayPartName(DayPart.MORNING)).isEqualTo("오전")
        assertThat(BundleNames.dayPartName(DayPart.AFTERNOON)).isEqualTo("오후")
        assertThat(BundleNames.dayPartName(DayPart.NIGHT)).isEqualTo("밤")
    }
}
