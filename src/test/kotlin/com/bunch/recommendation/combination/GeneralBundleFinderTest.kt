package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.GeneralTestSupport.DAY
import com.bunch.recommendation.combination.GeneralTestSupport.active
import com.bunch.recommendation.combination.GeneralTestSupport.day
import com.bunch.recommendation.combination.GeneralTestSupport.ids
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.ActivityCode.CAFE
import com.bunch.recommendation.domain.combination.ActivityCode.DRINKS
import com.bunch.recommendation.domain.combination.ActivityCode.MEAL
import com.bunch.recommendation.domain.combination.ActivityCode.SHOPPING
import com.bunch.recommendation.domain.combination.ActivityCode.SIGHTSEEING
import com.bunch.recommendation.domain.combination.ActivityCode.SPORTS
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.GeneralBundleFinder
import com.bunch.recommendation.domain.combination.GeneralChunker
import com.bunch.recommendation.domain.combination.GeneralResult
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * The general bundles of a day (recommended bundle specification 7, the scenarios of 13-2 and 13-3, and the examples of
 * the photo box specification 4.2.7 that the two have in common).
 */
class GeneralBundleFinderTest {
    private fun find(vararg groups: List<RecoPhoto>): GeneralResult =
        GeneralTestSupport.find(groups.toList().flatten())

    private fun photoIds(bundle: RecoBundle) = bundle.photos.map { it.photoId }

    private fun activities(result: GeneralResult): List<ActivityCode?> = result.bundles.map { it.activity }

    // --- 13-2 ---

    @Test
    fun `G1 lunch and dinner far apart are two bundles`() {
        val lunch = active(1, "12:00", 13, MEAL)
        val dinner = active(100, "18:00", 13, MEAL)

        val result = find(lunch, dinner)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(lunch), ids(dinner))
        assertThat(activities(result)).containsExactly(MEAL, MEAL)
        assertThat(result.bundles.last().trace.general!!.rules).contains("rule3_slot_change at 18:00 gap 4h0m")
    }

    @Test
    fun `G2 a dinner and a karaoke 30 minutes later are two bundles`() {
        val result = find(active(1, "19:00", 8, MEAL), active(100, "20:40", 12, DRINKS))

        assertThat(activities(result)).containsExactly(MEAL, DRINKS)
        assertThat(result.bundles.map { it.photoCount }).containsExactly(8, 12)
    }

    @Test
    fun `G3 a dinner and a cafe of 3 photos are one bundle`() {
        val dinner = active(1, "19:00", 15, MEAL)
        val cafe = active(100, "21:50", 3, CAFE)

        val result = find(dinner, cafe)

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().activity).isEqualTo(MEAL)
        assertThat(photoIds(result.bundles.single())).containsExactlyElementsOf(ids(dinner) + ids(cafe))
        assertThat(result.bundles.single().trace.general!!.rules).anyMatch { it.startsWith("rule6_join_section") }
    }

    @Test
    fun `G4 other members in the afternoon and in the evening are two bundles, by the members`() {
        val afternoon = active(1, "13:00", 8, CAFE, members = listOf(1, 2))
        val evening = active(100, "18:00", 6, CAFE, members = listOf(3, 4))

        val result = find(afternoon, evening)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(afternoon), ids(evening))
        val rules = result.bundles.first().trace.general!!.rules
        assertThat(rules).anyMatch { it.startsWith("rule2_people_change") }
        assertThat(rules).noneMatch { it.startsWith("rule3_slot_change") }
    }

    @Test
    fun `G5 a day of which the activity is mostly unknown is not recommended`() {
        val photos = active(1, "10:00", 10, null)

        val result = find(photos)

        assertThat(result.bundles).isEmpty()
        assertThat(result.notBundledPhotoIds).containsExactlyElementsOf(ids(photos))
    }

    @Test
    fun `G7 a whole day out of 60 photos is cut into sub bundles`() {
        val clusters = (0 until 6).flatMap {
            val start = LocalTime.of(9, 0).plusMinutes(85L * it).toString()
            active(it * 100L + 1, start, 10, SIGHTSEEING, step = 5)
        }

        val result = find(clusters)

        assertThat(result.bundles).hasSize(2)
        assertThat(result.bundles.map { it.subIndex }).containsExactly(1, 2)
        assertThat(result.bundles.map { it.photoCount }).containsExactly(30, 30)
        assertThat(activities(result)).containsOnly(SIGHTSEEING)
        assertThat(result.bundles.first().trace.general!!.rules).anyMatch { it.startsWith("rule7_split_big") }
    }

    @Test
    fun `G8 a dinner with a gap of 70 minutes in the same part of the day is one bundle`() {
        val result = find(active(1, "18:00", 7, MEAL), active(100, "20:10", 6, MEAL))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(13)
    }

    @Test
    fun `G9 a cafe and a lunch under 2 hours apart stay two bundles when both are enough`() {
        val result = find(active(1, "10:00", 10, CAFE), active(100, "13:00", 13, MEAL))

        assertThat(activities(result)).containsExactly(CAFE, MEAL)
    }

    @Test
    fun `G10 a shopping and a dinner 3 hours apart stay two bundles when both are enough`() {
        val result = find(active(1, "14:00", 13, SHOPPING), active(100, "19:00", 7, MEAL))

        assertThat(activities(result)).containsExactly(SHOPPING, MEAL)
    }

    @Test
    fun `G11 the same activity 5 hours apart, in two parts of the day, is two bundles`() {
        val morning = active(1, "10:00", 13, SHOPPING)
        val evening = active(100, "17:00", 13, SHOPPING)

        val result = find(morning, evening)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(morning), ids(evening))
    }

    @Test
    fun `G12 a sightseeing with 3 hours of travel in the middle is not cut`() {
        val result = find(active(1, "09:00", 13, SIGHTSEEING), active(100, "14:00", 13, SIGHTSEEING))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(26)
    }

    @Test
    fun `G13 a lunch with a gap of 70 minutes is not cut`() {
        val result = find(active(1, "11:30", 5, MEAL), active(100, "13:20", 5, MEAL))

        assertThat(result.bundles).hasSize(1)
    }

    @Test
    fun `G14 a lunch with a gap of 2 hours 10 minutes is cut when both sides are enough, a known limit`() {
        val result = find(active(1, "11:20", 6, MEAL), active(100, "14:20", 6, MEAL))

        assertThat(result.bundles).hasSize(2)
    }

    // --- 13-3 ---

    @Test
    fun `R1 and R2 a run with enough members is a bundle, and with few photos it is not`() {
        val crowd = find(active(1, "07:00", 10, SPORTS, members = listOf(1, 2, 3)))
        val few = find(active(1, "07:00", 4, SPORTS))

        assertThat(activities(crowd)).containsExactly(SPORTS)
        assertThat(few.bundles).isEmpty()
    }

    @Test
    fun `R4 a run and a brunch are two bundles when both are enough, and one when the brunch is small`() {
        val run = active(1, "07:00", 8, SPORTS)

        assertThat(activities(find(run, active(100, "08:40", 8, MEAL)))).containsExactly(SPORTS, MEAL)
        assertThat(activities(find(run, active(100, "08:40", 3, MEAL)))).containsExactly(SPORTS)
    }

    @Test
    fun `R4-1 a run in the morning and one in the evening are two bundles`() {
        val result = find(active(1, "07:00", 7, SPORTS), active(100, "19:00", 7, SPORTS))

        assertThat(activities(result)).containsExactly(SPORTS, SPORTS)
    }

    // --- the photo box specification 4.2.7 ---

    @Test
    fun `4_2_7 afternoon and evening groups four hours apart are two bundles, and a lonely evening photo is not one`() {
        assertThat(find(active(1, "13:00", 8, MEAL), active(100, "19:00", 7, MEAL)).bundles).hasSize(2)
        assertThat(find(active(1, "10:00", 8, MEAL), active(100, "19:00", 1, MEAL)).bundles).hasSize(1)
    }

    @Test
    fun `4_2_7 a day taken steadily is one bundle`() {
        val result = find(active(1, "11:00", 37, MEAL, step = 15))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(37)
    }

    @Test
    fun `4_2_7 photos over midnight are one bundle of the day that began, shown from the first to the last photo`() {
        val before = active(1, "23:10", 5, MEAL)
        val after = active(100, "00:20", 8, MEAL, date = "2026-10-20")

        val result = find(before, after)

        val bundle = result.bundles.single()
        assertThat(bundle.dayKey).isEqualTo(LocalDate.parse(DAY))
        assertThat(bundle.photoCount).isEqualTo(13)
        assertThat(bundle.startAt).isEqualTo(Instant.parse("2026-10-19T14:10:00Z"))
        assertThat(bundle.endAt).isEqualTo(Instant.parse("2026-10-19T16:30:00Z"))
    }

    @Test
    fun `4_2_7 a group with 6 photos and one member is not recommended`() {
        val result = find(active(1, "10:00", 8, MEAL, members = listOf(1)))

        assertThat(result.bundles).isEmpty()
        assertThat(result.notBundledPhotoIds).hasSize(8)
    }

    @Test
    fun `4_2_7 a day on which no photo has a time is one group of photos`() {
        val meal = AiAnalysis(
            activity = Reading(MEAL, 0.9),
            subject = PhotoSubject.FOOD,
            people = PeopleCount.P0
        )
        val photos = (1..8).map { photo(it.toLong(), time = null, members = listOf(1L + it % 2), analysis = meal) }

        val result = find(photos)

        val bundle = result.bundles.single()
        assertThat(bundle.photoCount).isEqualTo(8)
        assertThat(bundle.startAt).isEqualTo(Instant.parse("2026-10-18T20:00:00Z"))
        assertThat(bundle.endAt).isEqualTo(bundle.startAt)
        assertThat(bundle.trace.general!!.rules).containsExactly(GeneralBundleFinder.NO_TIME)
        assertThat(find(photos.take(5)).bundles).isEmpty()
    }

    // --- 7-10 and the bundle itself ---

    @Test
    fun `every photo is in one bundle or in one piece that is not a bundle, and none is in two`() {
        val photos = active(1, "10:00", 10, CAFE) + active(50, "12:30", 3, null, members = listOf(1)) +
            active(100, "18:00", 8, MEAL) + active(200, "21:00", 2, DRINKS)

        val result = find(photos)

        val all = result.bundles.flatMap { photoIds(it) } + result.notBundledPhotoIds
        assertThat(all).containsExactlyInAnyOrderElementsOf(ids(photos))
        assertThat(all).doesNotHaveDuplicates()
    }

    @Test
    fun `a photo with a date only is not in the result, on a day that has photos with a time`() {
        val dateOnly = photo(900, time = null, members = listOf(1))
        val result = find(active(1, "12:00", 13, MEAL), listOf(dateOnly))

        assertThat(result.bundles.flatMap { photoIds(it) }).doesNotContain(900L)
        assertThat(result.notBundledPhotoIds).doesNotContain(900L)
    }

    @Test
    fun `the order of the input does not change the result`() {
        val photos = active(1, "12:00", 13, MEAL) + active(100, "18:00", 13, MEAL) + active(200, "21:50", 3, CAFE)

        val straight = find(photos)
        val shuffled = find(photos.shuffled(Random(7)))

        assertThat(shuffled.bundles.map { photoIds(it) }).isEqualTo(straight.bundles.map { photoIds(it) })
        assertThat(shuffled.bundles.map { it.trace }).isEqualTo(straight.bundles.map { it.trace })
    }

    @Test
    fun `a bundle says what it is, how it was cut, and the time of its first and last photo`() {
        val lunch = active(1, "12:00", 13, MEAL)
        val dinner = active(100, "18:00", 13, MEAL)

        val bundle = find(lunch, dinner).bundles.last()

        assertThat(bundle.kind).isEqualTo(BundleKind.GENERAL)
        assertThat(bundle.eventType).isNull()
        assertThat(bundle.establishedBy).isNull()
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
        assertThat(trace.general!!.activity).isEqualTo(MEAL)
        assertThat(trace.general!!.rules.first()).isEqualTo(GeneralChunker.DAY)
        assertThat(trace.general!!.segments.map { it.activity }).containsExactly(MEAL)
    }

    @Test
    fun `a bundle has a group photo when a usable photo shows 3 people or more`() {
        val group = active(1, "12:00", 6, MEAL).mapIndexed { index, photo ->
            if (index == 0) photo.copy(analysis = photo.analysis.copy(people = PeopleCount.P3_PLUS)) else photo
        }

        assertThat(find(group).bundles.single().hasGroupPhoto).isTrue()
        assertThat(find(active(1, "12:00", 6, MEAL)).bundles.single().hasGroupPhoto).isFalse()
    }
}
