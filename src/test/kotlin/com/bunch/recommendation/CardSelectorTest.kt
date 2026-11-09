package com.bunch.recommendation

import com.bunch.recommendation.domain.CardSelection
import com.bunch.recommendation.domain.CardSelector
import com.bunch.recommendation.domain.PhotoSample
import com.bunch.recommendation.domain.RecommendationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class CardSelectorTest {
    private val selector = CardSelector()
    private val day = LocalDate.of(2026, 10, 19)
    private val nextDay = day.plusDays(1)
    private var nextId = 1L

    @Test
    fun `thirty photos of one member and a few of two others give ten with everyone in it`() {
        val thirtyPhotos = series(30, stepMinutes = 24)
        val fivePhotos = listOf(11, 13, 15, 17, 19).map { photo(minutes(it * 60 + 10), 2L) }
        val threePhotos = listOf(12, 16, 20).map { photo(minutes(it * 60 + 10), 3L) }
        val all = thirtyPhotos + fivePhotos + threePhotos

        val selection = selector.select(all)

        assertThat(selection.recommended).isTrue()
        assertThat(selection.photoIds).hasSize(10)
        assertThat(selection.photoCount).isEqualTo(38)
        assertThat(selection.uploaderIds).containsExactlyInAnyOrder(1L, 2L, 3L)
        val chosen = all.filter { it.photoId in selection.photoIds }
        assertThat(chosen.count { 1L in it.uploaderIds }).isGreaterThanOrEqualTo(1)
        assertThat(chosen.count { 2L in it.uploaderIds }).isGreaterThanOrEqualTo(1)
        assertThat(chosen.count { 3L in it.uploaderIds }).isGreaterThanOrEqualTo(1)
        assertThat(selection.photoIds).containsExactlyElementsOf(chosen.sortedBy { it.moment }.map { it.photoId })
    }

    @Test
    fun `ten or fewer photos are all taken`() {
        val photos = series(8, uploaderOf = ::alternating)

        val selection = selector.select(photos)

        assertThat(selection.photoIds).containsExactlyElementsOf(photos.map { it.photoId })
    }

    @Test
    fun `exactly ten photos are all taken`() {
        val photos = series(10, uploaderOf = ::alternating)

        assertThat(selector.select(photos).photoIds).hasSize(10)
    }

    @Test
    fun `eight members all get a photo when there are more than ten`() {
        val photos = (1L..8L).flatMap { member ->
            series(3, stepMinutes = 17, startMinute = 9 * 60 + member.toInt() * 3 * 17, uploaderOf = { member })
        }

        val selection = selector.select(photos)

        assertThat(selection.photoIds).hasSize(10)
        val chosen = photos.filter { it.photoId in selection.photoIds }
        (1L..8L).forEach { member -> assertThat(chosen.any { member in it.uploaderIds }).isTrue() }
    }

    @Test
    fun `the rest of the places are spread over the time of the cluster`() {
        // Twenty photos from 10:00 to 19:30. The assertions below depend on these times.
        val many = series(20, stepMinutes = 30, startMinute = 10 * 60)
        val few = listOf(photo(minutes(15 * 60), 2L), photo(minutes(15 * 60 + 5), 2L))
        val all = many + few

        val selection = selector.select(all)

        val chosen = all.filter { it.photoId in selection.photoIds }
        val times = chosen.map { it.capturedTime!!.toSecondOfDay() / 60 }.sorted()
        assertThat(times.first()).isLessThanOrEqualTo(11 * 60)
        assertThat(times.last()).isGreaterThanOrEqualTo(19 * 60)
        assertThat(times.zipWithNext { a, b -> b - a }.max()).isLessThanOrEqualTo(150)
    }

    @Test
    fun `the photos of one member are spread over the day even when others already cover a time`() {
        // Both members have a photo every hour from 09:00 to 20:00. The other member's photos have the lower IDs, so
        // they win every tie. Judging only by the time of all chosen photos leaves the member with a few photos in
        // one part of the day (4.2.8 rule 2).
        val other = series(12, stepMinutes = 60, startMinute = 9 * 60, uploaderOf = { 2L })
        val mine = series(12, stepMinutes = 60, startMinute = 9 * 60, uploaderOf = { 1L })
        val all = other + mine

        val selection = selector.select(all)

        val chosen = all.filter { it.photoId in selection.photoIds }
        listOf(1L, 2L).forEach { member ->
            val hours = chosen.filter { member in it.uploaderIds }.map { it.capturedTime!!.hour }
            assertThat(hours.max() - hours.min()).isGreaterThanOrEqualTo(6)
        }
    }

    @Test
    fun `no two chosen photos share a time while other times are free`() {
        val other = series(12, stepMinutes = 60, startMinute = 9 * 60, uploaderOf = { 2L })
        val mine = series(12, stepMinutes = 60, startMinute = 9 * 60, uploaderOf = { 1L })
        val all = other + mine

        val selection = selector.select(all)

        val hours = all.filter { it.photoId in selection.photoIds }.map { it.capturedTime!!.hour }
        assertThat(hours).doesNotHaveDuplicates()
    }

    @Test
    fun `the first photo of a member is the one farthest from what others chose`() {
        val lone = photo(minutes(12 * 60), 2L)
        val nearTheLone = listOf(12 * 60 + 30, 13 * 60, 14 * 60, 15 * 60, 16 * 60).map { photo(minutes(it), 1L) }
        val farFromTheLone = photo(minutes(20 * 60), 1L)
        val narrow = CardSelector(RecommendationSettings(maxCardPhotos = 2))

        val selection = narrow.select(listOf(lone) + nearTheLone + farFromTheLone)

        assertThat(selection.photoIds).containsExactlyInAnyOrder(lone.photoId, farFromTheLone.photoId)
    }

    @Test
    fun `a member whose photos have no time still gets a photo`() {
        val timed = series(10)
        val untimed = listOf(photo(null, 2L), photo(null, 2L))

        val selection = selector.select(timed + untimed)

        assertThat(selection.photoIds).hasSize(10)
        assertThat(selection.photoIds.count { it in untimed.map { photo -> photo.photoId } }).isEqualTo(1)
        assertThat(selection.photoIds.last()).isEqualTo(untimed.first().photoId)
    }

    @Test
    fun `a photo uploaded together gives its only member a place`() {
        val together = photo(minutes(15 * 60), 1L, 2L)
        val others = series(14, stepMinutes = 40)

        val selection = selector.select(others + together)

        assertThat(selection.photoIds).hasSize(10)
        assertThat(selection.photoIds).contains(together.photoId)
        assertThat(selection.uploaderIds).containsExactlyInAnyOrder(1L, 2L)
        assertThat(selection.photoCount).isEqualTo(15)
    }

    @Test
    fun `fewer than six photos after the bursts are not recommended`() {
        val bursts = listOf("10:00" to 1L, "11:00" to 1L, "12:00" to 2L, "13:00" to 2L)
            .flatMap { (time, member) -> burstPair(LocalTime.parse(time), member) }

        val selection = selector.select(bursts)

        assertThat(selection.recommended).isFalse()
        assertThat(selection.photoIds).isEmpty()
        assertThat(selection.photoCount).isEqualTo(4)
    }

    @Test
    fun `five or six photos after a burst decide the recommendation`() {
        val singles = series(5, uploaderOf = ::alternating)
        val pair = burstPair(LocalTime.parse("18:00"), 1L)

        // Five singles and a pair of shots leave six photos, four singles and a pair leave five.
        assertThat(selector.select(singles + pair).recommended).isTrue()
        assertThat(selector.select(singles.drop(1) + pair).recommended).isFalse()
    }

    @Test
    fun `a bunch of photos from one member is not recommended`() {
        val photos = series(8)

        val selection = selector.select(photos)

        assertThat(selection.recommended).isFalse()
        assertThat(selection.photoIds).isEmpty()
        assertThat(selection.uploaderIds).containsExactly(1L)
        assertThat(selection.photoCount).isEqualTo(8)
    }

    @Test
    fun `six photos from two members are recommended`() {
        val photos = series(6, uploaderOf = { if (it < 3) 1L else 2L })

        val selection = selector.select(photos)

        assertThat(selection.recommended).isTrue()
        assertThat(selection.photoIds).containsExactlyElementsOf(photos.map { it.photoId })
    }

    @Test
    fun `the cards of a cluster over midnight follow the order of a recommended bundle`() {
        val firstLate = photo(LocalTime.parse("23:10"), 1L)
        val secondLate = photo(LocalTime.parse("23:40"), 2L)
        val untimedFirstDay = photo(null, 1L)
        val firstEarly = photo(LocalTime.parse("00:10"), 2L, date = nextDay)
        val secondEarly = photo(LocalTime.parse("00:50"), 1L, date = nextDay)
        val untimedNextDay = photo(null, 2L, date = nextDay)

        val selection = selector.select(
            listOf(untimedNextDay, secondEarly, untimedFirstDay, firstEarly, secondLate, firstLate)
        )

        assertThat(selection.photoIds).containsExactly(
            firstLate.photoId,
            secondLate.photoId,
            untimedFirstDay.photoId,
            firstEarly.photoId,
            secondEarly.photoId,
            untimedNextDay.photoId
        )
    }

    @Test
    fun `photos without a time come after the timed ones of the day in upload order`() {
        val firstUntimed = photo(null, 1L)
        val secondUntimed = photo(null, 2L)
        val timed = series(4, uploaderOf = ::alternating)

        val selection = selector.select(listOf(secondUntimed) + timed.reversed() + firstUntimed)

        assertThat(selection.photoIds).containsExactlyElementsOf(
            timed.map { it.photoId } + firstUntimed.photoId + secondUntimed.photoId
        )
    }

    @Test
    fun `the settings change the largest number of cards`() {
        val photos = series(12, uploaderOf = ::alternating)
        val small = CardSelector(RecommendationSettings(maxCardPhotos = 8))

        assertThat(small.select(photos).photoIds).hasSize(8)
        assertThat(selector.select(photos).photoIds).hasSize(10)
    }

    @Test
    fun `the largest number of cards also decides when everything is taken`() {
        val photos = series(9, uploaderOf = ::alternating)
        val small = CardSelector(RecommendationSettings(maxCardPhotos = 8))

        assertThat(small.select(photos).photoIds).hasSize(8)
        assertThat(selector.select(photos).photoIds).hasSize(9)
    }

    @Test
    fun `a photo uploaded together belongs to the member who uploaded it first when both have as many photos`() {
        val narrow = CardSelector(RecommendationSettings(maxCardPhotos = 2))
        val shared = photo(minutes(18 * 60), 2L, 1L)

        val selection = narrow.select(togetherCluster(shared))

        assertThat(selection.photoIds).doesNotContain(shared.photoId)
        assertThat(selection.photoIds).hasSize(2)
    }

    @Test
    fun `a photo uploaded together belongs to the other member when that member uploaded it first`() {
        val narrow = CardSelector(RecommendationSettings(maxCardPhotos = 2))
        val shared = photo(minutes(18 * 60), 1L, 2L)

        val selection = narrow.select(togetherCluster(shared))

        assertThat(selection.photoIds).contains(shared.photoId)
        assertThat(selection.photoIds).hasSize(2)
    }

    @Test
    fun `a photo uploaded together belongs to the member with fewer photos and the others cannot take it`() {
        val narrow = CardSelector(RecommendationSettings(maxCardPhotos = 3))
        val shared = photo(minutes(18 * 60), 1L, 2L)
        val fewer = listOf(photo(minutes(13 * 60), 2L), shared)
        val middle = listOf(13 * 60 + 10, 13 * 60 + 20, 13 * 60 + 30).map { photo(minutes(it), 3L) }
        val most = listOf(9 * 60 + 30, 10 * 60 + 30, 11 * 60 + 30, 12 * 60, 12 * 60 + 15, 12 * 60 + 30)
            .map { photo(minutes(it), 1L) }

        val selection = narrow.select(most + middle + fewer)

        assertThat(selection.photoIds).doesNotContain(shared.photoId)
        assertThat(selection.photoIds).hasSize(3)
        assertThat(selection.uploaderIds).containsExactlyInAnyOrder(1L, 2L, 3L)
    }

    @Test
    fun `when places are fewer than members the members with fewer photos go first`() {
        val many = series(5)
        val two = series(2, startMinute = 13 * 60, uploaderOf = { 2L })
        val one = photo(minutes(16 * 60), 3L)
        val narrow = CardSelector(RecommendationSettings(maxCardPhotos = 2))

        val selection = narrow.select(many + two + one)

        assertThat(selection.photoIds).hasSize(2)
        assertThat(selection.photoIds).contains(one.photoId)
        assertThat(selection.photoIds.filter { it in many.map { photo -> photo.photoId } }).isEmpty()
    }

    @Test
    fun `nine photos left after the bursts are all taken`() {
        val spread = series(6, stepMinutes = 60, uploaderOf = ::alternating)
        val pairs = listOf(14, 16, 18).flatMap { hour -> burstPair(LocalTime.of(hour, 0), 1L) }

        val selection = selector.select(spread + pairs)

        assertThat(selection.photoCount).isEqualTo(9)
        assertThat(selection.photoIds).hasSize(9)
    }

    @Test
    fun `the same photos give the same cards`() {
        val photos = series(25, stepMinutes = 20, uploaderOf = { (it % 3 + 1).toLong() })

        assertThat(selector.select(photos)).isEqualTo(selector.select(photos.reversed()))
    }

    @Test
    fun `no photos give no cards`() {
        assertThat(selector.select(emptyList())).isEqualTo(CardSelection(emptyList(), 0, emptySet(), false))
    }

    private fun photo(
        time: LocalTime?,
        vararg uploaders: Long,
        date: LocalDate = day,
        fingerprint: Long? = null
    ) = PhotoSample(nextId++, date, time, uploaders.toSet(), fingerprint = fingerprint)

    // Photos taken at an even distance from the start. By default one member uploaded all of them.
    private fun series(
        count: Int,
        stepMinutes: Int = 30,
        startMinute: Int = 10 * 60,
        uploaderOf: (Int) -> Long = { 1L }
    ) = (0 until count).map { photo(minutes(startMinute + it * stepMinutes), uploaderOf(it)) }

    // Members 1 and 2 have four photos each, the shared one counted, and member 3 has three. The shared photo is the
    // last of the day, so the member who may take it picks it for being the farthest from the others.
    private fun togetherCluster(shared: PhotoSample): List<PhotoSample> =
        listOf(13 * 60 + 10, 13 * 60 + 20, 13 * 60 + 30).map { photo(minutes(it), 3L) } +
            listOf(9 * 60 + 30, 10 * 60 + 30, 11 * 60 + 30).map { photo(minutes(it), 1L) } +
            listOf(12 * 60, 12 * 60 + 15, 12 * 60 + 30).map { photo(minutes(it), 2L) } +
            shared

    // Two members who take turns: 1, 2, 1, 2, ...
    private fun alternating(index: Int): Long = if (index % 2 == 0) 1L else 2L

    // Two shots of one member a second apart that look the same.
    private fun burstPair(time: LocalTime, member: Long) = listOf(
        photo(time, member, fingerprint = 0L),
        photo(time.plusSeconds(1), member, fingerprint = 0L)
    )

    private fun minutes(minuteOfDay: Int): LocalTime = LocalTime.ofSecondOfDay(minuteOfDay * 60L)
}
