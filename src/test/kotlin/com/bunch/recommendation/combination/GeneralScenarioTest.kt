package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.GeneralTestSupport.ids
import com.bunch.recommendation.combination.GeneralTestSupport.shots
import com.bunch.recommendation.domain.combination.BundleEligibility
import com.bunch.recommendation.domain.combination.GeneralBundleFinder
import com.bunch.recommendation.domain.combination.GeneralResult
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.random.Random

/**
 * The cases of the recommended bundle specification as scenarios (8-2 V10): the general cases G1 to G25 and the
 * repeated activity R1 to R5 (5-1, 5-2), the points of 3-5 (no gap and no overlap), and the known limits of 7. Each
 * case is the photos of the table and the result it gives. What a rule alone decides is also in the test of that
 * rule.
 */
class GeneralScenarioTest {
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

        val bundle = result.bundles.single()
        assertThat(photoIds(bundle)).containsExactlyElementsOf(ids(cafe) + ids(parkByA) + ids(parkByB))
        assertThat(rulesOf(bundle)).noneMatch { it.startsWith("rule2_slot_change") || it.startsWith("edge_trim") }
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


    // --- the cases that need the edge trimming or the fifth rule (3-3, 3-2) ---

    @Test
    fun `G18 a dinner of 12 photos and 3 photos of a bar that one member took are one bundle of the dinner`() {
        val dinner = shots(1, "19:00", 12)
        val bar = shots(100, "22:50", 3, members = listOf(1))

        val result = find(dinner, bar)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(dinner))
        assertThat(result.notBundledPhotoIds).containsExactlyElementsOf(ids(bar))
        assertThat(rulesOf(result.bundles.single())).contains("edge_trim_back at 22:50 gap 2h0m")
    }

    @Test
    fun `G18 the bar stays in the bundle when its photos are in a moment the members took photos together`() {
        val dinner = shots(1, "19:00", 12)
        val bar = shots(100, "22:50", 3)

        val result = find(dinner, bar)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(dinner) + ids(bar))
    }

    @Test
    fun `G19 a day of a trip is one to three bundles, as the gaps are`() {
        val airport = shots(1, "08:00", 6, step = 5)
        val sights = shots(100, "13:00", 12)
        val longSights = shots(100, "13:00", 30)
        val lodgingLate = shots(200, "21:00", 6, step = 5)
        val lodgingSoon = shots(300, "19:30", 6, step = 5)

        assertThat(find(airport, sights, lodgingLate).bundles).hasSize(3)
        assertThat(find(airport, longSights, lodgingSoon).bundles).hasSize(2)
    }

    @Test
    fun `G20 and G21 the content is not read, so photos of a funeral hall and a receipt count like any other`() {
        val result = find(shots(1, "14:00", 10))

        assertThat(result.bundles).hasSize(1)
        assertThat(result.bundles.single().photoCount).isEqualTo(10)
    }

    @Test
    fun `G23 3 photos on the way to work and a gathering of 20 photos are one bundle of the gathering`() {
        val work = shots(1, "08:00", 3, members = listOf(1))
        val meeting = shots(10, "10:00", 20, members = listOf(1, 2, 3), step = 9)

        val result = find(work, meeting)

        assertThat(result.bundles.map { photoIds(it) }).containsExactly(ids(meeting))
        assertThat(result.notBundledPhotoIds).containsExactlyElementsOf(ids(work))
    }

    // --- 5-2 (the rest) ---

    @Test
    fun `R1 and R2 a run every day is a bundle on the days that are enough, and none on the days that are not`() {
        val crowded = find(shots(1, "07:00", 10, members = listOf(1, 2, 3), date = "2026-11-12"))
        val thin = find(shots(1, "07:00", 4, members = listOf(1, 2), date = "2026-11-13"))

        assertThat(crowded.bundles).hasSize(1)
        assertThat(thin.bundles).isEmpty()
    }

    @Test
    fun `R3 a race day is a general bundle like any other`() {
        val result = find(shots(1, "07:00", 14, members = listOf(1, 2, 3), step = 8, date = "2026-11-16"))

        val bundle = result.bundles.single()
        assertThat(bundle.activity).isNull()
        assertThat(bundle.eventType).isNull()
    }

    @Test
    fun `R5 similar runs on many days make a bundle each day by the same rules`() {
        val days = listOf("2026-11-10", "2026-11-11", "2026-11-12").map {
            find(shots(1, "07:00", 10, members = listOf(1, 2, 3), date = it))
        }

        assertThat(days.map { it.bundles.size }).containsOnly(1)
        assertThat(days.map { rulesOf(it.bundles.single()) }.distinct()).hasSize(1)
    }

    // --- 3-5: no gap and no overlap ---

    @Test
    fun `3-5 every photo is in one piece, every bundle is recommended, and any order gives the same`() {
        val eligibility = BundleEligibility()
        for (seed in 1..40) {
            val random = Random(seed)
            val photos = randomDay(random)

            val day = ready(photos)
            val result = GeneralBundleFinder().find(day)

            val placed = result.bundles.flatMap { photoIds(it) } + result.notBundledPhotoIds
            assertThat(placed).`as`("seed $seed").containsExactlyInAnyOrderElementsOf(ids(photos))
            assertThat(placed).`as`("seed $seed").doesNotHaveDuplicates()
            for (bundle in result.bundles) {
                val own = day.photos.filter { it.photoId in photoIds(bundle) }
                assertThat(eligibility.isRecommendable(own, day.duplicateGroups)).`as`("seed $seed").isTrue()
            }

            val shuffled = GeneralBundleFinder().find(ready(photos.shuffled(Random(seed + 1000))))
            assertThat(shuffled.bundles.map { photoIds(it) }).`as`("seed $seed")
                .isEqualTo(result.bundles.map { photoIds(it) })
            assertThat(shuffled.notBundledPhotoIds).`as`("seed $seed").isEqualTo(result.notBundledPhotoIds)
        }
    }

    // Some gatherings of one to four members at times of the day, each with a few to many photos.
    private fun randomDay(random: Random): List<RecoPhoto> {
        var nextId = 1L
        return (0 until random.nextInt(2, 7)).flatMap {
            val hour = random.nextInt(6, 17)
            val members = (1L..4L).shuffled(random).take(random.nextInt(1, 5))
            val count = random.nextInt(1, 29)
            val first = nextId
            nextId += count
            val start = LocalTime.of(hour, random.nextInt(0, 60)).toString()
            shots(first, start, count, members, step = random.nextLong(2, 15))
        }.distinctBy { it.photoId }
    }
}
