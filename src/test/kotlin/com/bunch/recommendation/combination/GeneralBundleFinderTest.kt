package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.DAY
import com.bunch.recommendation.combination.GeneralTestSupport.ids
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.GeneralChunker
import com.bunch.recommendation.domain.combination.GeneralResult
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * The general bundles of a day cut by the first four rules (recommended bundle specification 3-2, the cases of 5-1 and
 * 5-2 that these rules decide). The cases of the edge trimming (G18, G23) and of the big pieces (G7 in its full form)
 * are decided by the later rules and are in the scenario tests.
 */
class GeneralBundleFinderTest {
    private fun find(vararg groups: List<RecoPhoto>): GeneralResult =
        GeneralTestSupport.find(groups.toList().flatten())

    private fun photoIds(bundle: RecoBundle) = bundle.photos.map { it.photoId }

    private fun rulesOf(bundle: RecoBundle) = bundle.trace.general!!.rules

    // --- 5-1 ---

    @Test
    fun `G1 lunch and dinner far apart are two bundles`() {
        val lunch = shots(1, "12:00", 13)
        val dinner = shots(100, "18:00", 13)

        val result = find(lunch, dinner)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(lunch), ids(dinner))
        assertThat(rulesOf(result.bundles.last())).contains("rule2_slot_change at 18:00 gap 4h0m")
    }

    @Test
    fun `G2 a dinner and a karaoke 30 minutes later are one bundle`() {
        val result = find(shots(1, "19:00", 8), shots(100, "20:40", 12))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(20)
    }

    @Test
    fun `G3 a dinner and a cafe of 3 photos are one bundle`() {
        val dinner = shots(1, "19:00", 15)
        val cafe = shots(100, "21:50", 3)

        val result = find(dinner, cafe)

        assertThat(result.bundles).hasSize(1)
        assertThat(photoIds(result.bundles.single())).containsExactlyElementsOf(ids(dinner) + ids(cafe))
    }

    @Test
    fun `G4 other members in the afternoon and in the evening are two bundles, by the members`() {
        val afternoon = shots(1, "13:00", 8, members = listOf(1, 2))
        val evening = shots(100, "18:00", 6, members = listOf(3, 4))

        val result = find(afternoon, evening)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(afternoon), ids(evening))
        val rules = rulesOf(result.bundles.first())
        assertThat(rules).anyMatch { it.startsWith("rule1_people_change") }
        assertThat(rules).noneMatch { it.startsWith("rule2_slot_change") }
    }

    @Test
    fun `G5 10 photos of one member in the morning and 2 of another at night are bundled but not recommended`() {
        val morning = shots(1, "09:00", 10, members = listOf(1))
        val night = shots(100, "22:00", 2, members = listOf(2))

        val result = find(morning, night)

        assertThat(result.bundles).isEmpty()
        assertThat(result.notBundledPhotoIds).containsExactlyElementsOf(ids(morning) + ids(night))
    }

    @Test
    fun `G6 a party that goes on from the afternoon to the night is one bundle`() {
        val result = find(shots(1, "17:00", 25))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(25)
    }

    @Test
    fun `G7 a whole day out of 60 photos is cut into sub bundles, at the gaps that are as long`() {
        val clusters = (0 until 6).flatMap {
            val start = LocalTime.of(9, 0).plusMinutes(85L * it).toString()
            shots(it * 100L + 1, start, 10, step = 5)
        }

        val result = find(clusters)

        assertThat(result.bundles).hasSize(2)
        assertThat(result.bundles.map { it.subIndex }).containsExactly(1, 2)
        assertThat(result.bundles.map { it.photoCount }).containsExactly(30, 30)
        assertThat(rulesOf(result.bundles.first())).anyMatch { it.startsWith("rule5_split_big") }
    }

    @Test
    fun `G8 a dinner with a gap of 70 minutes in the same part of the day is one bundle`() {
        val result = find(shots(1, "18:00", 7), shots(100, "20:10", 6))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(13)
    }

    @Test
    fun `G9 a cafe and a lunch under 2 hours apart are one bundle`() {
        val result = find(shots(1, "10:00", 10), shots(100, "13:00", 13))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(23)
    }

    @Test
    fun `G10 a shopping and a dinner 3 hours apart are two bundles when both are recommended`() {
        val result = find(shots(1, "14:00", 13), shots(100, "19:00", 7))

        assertThat(result.bundles.map { it.photoCount }).containsExactly(13, 7)
    }

    @Test
    fun `G11 the same thing 5 hours apart, in two parts of the day, is two bundles`() {
        val morning = shots(1, "10:00", 13)
        val evening = shots(100, "17:00", 13)

        val result = find(morning, evening)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(morning), ids(evening))
    }

    @Test
    fun `G12 a day out with 3 hours of travel in the middle is cut in two, a known limit`() {
        val result = find(shots(1, "09:00", 13), shots(100, "14:00", 13))

        assertThat(result.bundles).hasSize(2)
    }

    @Test
    fun `G13 a lunch with a gap of 70 minutes is not cut`() {
        val result = find(shots(1, "11:30", 5), shots(100, "13:20", 5))

        assertThat(result.bundles).hasSize(1)
    }

    @Test
    fun `G14 a lunch with a gap of 2 hours 10 minutes is cut when both sides are recommended, a known limit`() {
        val result = find(shots(1, "11:20", 6), shots(100, "14:20", 6))

        assertThat(result.bundles).hasSize(2)
    }

    @Test
    fun `G15 and G16 photos before and after a show or a wedding, under 4 hours apart, are one bundle`() {
        assertThat(find(shots(1, "18:30", 6), shots(100, "21:50", 6)).bundles).hasSize(1)
        assertThat(find(shots(1, "13:00", 6), shots(100, "15:30", 6)).bundles).hasSize(1)
    }

    @Test
    fun `G17 photos before and after a show that cross two parts of the day are two bundles, a known limit`() {
        val result = find(shots(1, "17:00", 6, step = 5), shots(100, "21:30", 6, step = 5))

        assertThat(result.bundles).hasSize(2)
    }

    @Test
    fun `G22 two members who took photos one after the other 25 minutes apart are one bundle`() {
        val a = shots(1, "19:00", 5, members = listOf(1))
        val b = shots(10, "20:05", 6, members = listOf(2), step = 11)

        val result = find(a, b)

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(11)
    }

    @Test
    fun `G24 a morning of two members and a lone member in the evening, only the first is recommended`() {
        val morning = shots(1, "09:00", 12)
        val evening = shots(100, "13:50", 5, members = listOf(3))

        val result = find(morning, evening)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(morning))
        assertThat(result.notBundledPhotoIds).containsExactlyElementsOf(ids(evening))
    }

    @Test
    fun `G25 a morning cafe and an afternoon park whose members took no photo together are one bundle`() {
        val cafe = shots(1, "09:00", 8)
        val parkByA = shots(100, "12:40", 5, members = listOf(1), step = 1)
        val parkByB = shots(110, "13:25", 3, members = listOf(2), step = 1)

        val result = find(cafe, parkByA, parkByB)

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(16)
    }

    // --- 5-2 ---

    @Test
    fun `R1 and R2 a run with enough members is a bundle, and with few photos it is not`() {
        val crowd = find(shots(1, "07:00", 10, members = listOf(1, 2, 3)))
        val few = find(shots(1, "07:00", 4))

        assertThat(crowd.bundles).hasSize(1)
        assertThat(few.bundles).isEmpty()
    }

    @Test
    fun `R4 a run and a brunch right after it are one bundle`() {
        val result = find(shots(1, "07:00", 8), shots(100, "08:40", 8))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(16)
    }

    @Test
    fun `R4-1 a run in the morning and one in the evening are two bundles`() {
        val result = find(shots(1, "07:00", 7), shots(100, "19:00", 7))

        assertThat(result.bundles).hasSize(2)
    }

    // --- the photo box specification 4.2.7 ---

    @Test
    fun `4_2_7 afternoon and evening groups four hours apart are two bundles, and a lonely evening photo is not one`() {
        assertThat(find(shots(1, "13:00", 8), shots(100, "19:00", 7)).bundles).hasSize(2)
        assertThat(find(shots(1, "10:00", 8), shots(100, "19:00", 1)).bundles).hasSize(1)
    }

    @Test
    fun `4_2_7 a day taken steadily is one bundle`() {
        val result = find(shots(1, "11:00", 37, step = 15))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(37)
    }

    @Test
    fun `4_2_7 photos over midnight are one bundle of the day that began, shown from the first to the last photo`() {
        val before = shots(1, "23:10", 5)
        val after = shots(100, "00:20", 8, date = "2026-10-20")

        val result = find(before, after)

        val bundle = result.bundles.single()
        assertThat(bundle.dayKey).isEqualTo(LocalDate.parse(DAY))
        assertThat(bundle.photoCount).isEqualTo(13)
        assertThat(bundle.startAt).isEqualTo(Instant.parse("2026-10-19T14:10:00Z"))
        assertThat(bundle.endAt).isEqualTo(Instant.parse("2026-10-19T16:30:00Z"))
    }

    @Test
    fun `4_2_7 a group with 6 photos and one member is not recommended`() {
        val result = find(shots(1, "10:00", 8, members = listOf(1)))

        assertThat(result.bundles).isEmpty()
        assertThat(result.notBundledPhotoIds).hasSize(8)
    }

    @Test
    fun `a day on which no photo has a time has no bundle, and all its photos are not bundled`() {
        val photos = (1..8).map { photo(it.toLong(), time = null, members = listOf(1L + it % 2)) }

        val result = find(photos)

        assertThat(result.bundles).isEmpty()
        assertThat(result.notBundledPhotoIds).containsExactlyElementsOf(ids(photos))
    }

    // --- 3-5 and the bundle itself ---

    @Test
    fun `every photo is in one bundle or in one piece that is not a bundle, and none is in two`() {
        val photos = shots(1, "10:00", 10) + shots(50, "12:30", 3, members = listOf(1)) +
            shots(100, "18:00", 8) + shots(200, "21:00", 2)

        val result = find(photos)

        val all = result.bundles.flatMap { photoIds(it) } + result.notBundledPhotoIds
        assertThat(all).containsExactlyInAnyOrderElementsOf(ids(photos))
        assertThat(all).doesNotHaveDuplicates()
    }

    @Test
    fun `a photo with a date only is not in the result, on a day that has photos with a time`() {
        val dateOnly = photo(900, time = null, members = listOf(1))
        val result = find(shots(1, "12:00", 13), listOf(dateOnly))

        assertThat(result.bundles.flatMap { photoIds(it) }).doesNotContain(900L)
        assertThat(result.notBundledPhotoIds).doesNotContain(900L)
    }

    @Test
    fun `the order of the input does not change the result`() {
        val photos = shots(1, "12:00", 13) + shots(100, "18:00", 13) + shots(200, "21:50", 3)

        val straight = find(photos)
        val shuffled = find(photos.shuffled(Random(7)))

        assertThat(shuffled.bundles.map { photoIds(it) }).isEqualTo(straight.bundles.map { photoIds(it) })
        assertThat(shuffled.bundles.map { it.trace }).isEqualTo(straight.bundles.map { it.trace })
    }

    @Test
    fun `a bundle says what it is, how it was cut, and the time of its first and last photo`() {
        val lunch = shots(1, "12:00", 13)
        val dinner = shots(100, "18:00", 13)

        val bundle = find(lunch, dinner).bundles.last()

        assertThat(bundle.kind).isEqualTo(BundleKind.GENERAL)
        assertThat(bundle.eventType).isNull()
        assertThat(bundle.establishedBy).isNull()
        assertThat(bundle.activity).isNull()
        assertThat(bundle.subIndex).isNull()
        assertThat(bundle.coverPhotoId).isNull()
        assertThat(bundle.photos).allMatch { it.role == PhotoRole.GENERAL && it.cardPosition == null }
        assertThat(bundle.startAt).isEqualTo(Instant.parse("2026-10-19T09:00:00Z"))
        assertThat(bundle.endAt).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"))
        assertThat(bundle.uploaderCount).isEqualTo(2)
        val trace = bundle.trace
        assertThat(trace.kind).isEqualTo(BundleKind.GENERAL)
        assertThat(trace.counts.photos).isEqualTo(13)
        assertThat(trace.configVersion).isEqualTo("reco-cfg-1")
        assertThat(trace.event).isNull()
        assertThat(trace.general!!.activity).isNull()
        assertThat(trace.general!!.rules.first()).isEqualTo(GeneralChunker.DAY)
        assertThat(trace.general!!.segments).isEmpty()
    }

    @Test
    fun `a bundle has a group photo when a usable photo shows 3 people or more`() {
        val group = shots(1, "12:00", 6).mapIndexed { index, photo ->
            if (index == 0) photo.copy(analysis = photo.analysis.copy(people = PeopleCount.P3_PLUS)) else photo
        }

        assertThat(find(group).bundles.single().hasGroupPhoto).isTrue()
        assertThat(find(shots(1, "12:00", 6)).bundles.single().hasGroupPhoto).isFalse()
    }
}
