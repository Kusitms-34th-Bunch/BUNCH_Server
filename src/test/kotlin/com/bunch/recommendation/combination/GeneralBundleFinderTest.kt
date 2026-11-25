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
