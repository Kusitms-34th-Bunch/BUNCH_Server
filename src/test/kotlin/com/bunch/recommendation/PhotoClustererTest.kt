package com.bunch.recommendation

import com.bunch.recommendation.domain.PhotoCluster
import com.bunch.recommendation.domain.PhotoClusterer
import com.bunch.recommendation.domain.PhotoSample
import com.bunch.recommendation.domain.RecommendationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class PhotoClustererTest {
    private val clusterer = PhotoClusterer()
    private val day = LocalDate.of(2026, 10, 19)
    private val nextDay = day.plusDays(1)
    private var nextId = 1L

    @Test
    fun `a gap of four hours splits the day into two clusters`() {
        val afternoon = burst("13:00", "15:00", 8)
        val evening = burst("19:00", "22:00", 7)

        val clusters = clusterer.cluster(afternoon + evening)

        assertThat(groups(clusters)).containsExactly(ids(afternoon), ids(evening))
    }

    @Test
    fun `a gap just under four hours does not split the day`() {
        val afternoon = burst("13:00", "15:00", 3)
        val evening = burst("18:59", "19:30", 3)

        assertThat(clusterer.cluster(afternoon + evening)).hasSize(1)
    }

    @Test
    fun `every side of a split needs three photos`() {
        val three = burst("13:00", "14:00", 3)
        val twoLater = burst("19:00", "19:30", 2)
        val threeLater = burst("19:00", "19:30", 3)
        val twoEarlier = burst("08:00", "08:30", 2)

        assertThat(clusterer.cluster(three + twoLater)).hasSize(1)
        assertThat(clusterer.cluster(twoEarlier + burst("13:00", "14:00", 3))).hasSize(1)
        assertThat(clusterer.cluster(three + threeLater)).hasSize(2)
    }

    @Test
    fun `eight morning photos and one evening photo stay one cluster`() {
        val morning = burst("10:00", "12:00", 8)
        val evening = photo("19:00")

        val clusters = clusterer.cluster(morning + evening)

        assertThat(groups(clusters)).containsExactly(ids(morning) + evening.photoId)
    }

    @Test
    fun `steady shooting from morning to evening is one cluster`() {
        val all = burst("11:00", "20:00", 20)

        assertThat(groups(clusterer.cluster(all))).containsExactly(ids(all))
    }

    @Test
    fun `the longest gap is cut first`() {
        val first = burst("08:00", "09:00", 3)
        val second = burst("13:00", "13:30", 2)
        val third = burst("18:30", "19:30", 3)

        // The gaps are 4 hours then 5 hours. Cutting the 5 hour gap first leaves only two photos behind the 4 hour
        // gap, so that one is not cut. Cutting the earlier 4 hour gap first would give a different result.
        val clusters = clusterer.cluster(first + second + third)

        assertThat(groups(clusters)).containsExactly(ids(first + second), ids(third))
    }

    @Test
    fun `members who do not overlap split the day even when the gap is short`() {
        val firstPair = burst("13:00", "16:00", 8, uploaders = setOf(1L, 2L))
        val secondPair = burst("18:00", "21:00", 6, uploaders = setOf(3L, 4L))

        val clusters = clusterer.cluster(firstPair + secondPair)

        assertThat(groups(clusters)).containsExactly(ids(firstPair), ids(secondPair))
    }

    @Test
    fun `members who do not overlap split the day even when the gap is minutes`() {
        val first = burst("13:00", "13:20", 3, uploaders = setOf(1L))
        val second = burst("13:30", "13:50", 3, uploaders = setOf(2L))

        assertThat(groups(clusterer.cluster(first + second))).containsExactly(ids(first), ids(second))
    }

    @Test
    fun `one shared member keeps the day together`() {
        val before = burst("13:00", "16:00", 8, uploaders = setOf(1L, 2L))
        val after = burst("18:00", "21:00", 6, uploaders = setOf(2L, 3L))

        assertThat(clusterer.cluster(before + after)).hasSize(1)
    }

    @Test
    fun `members who do not overlap are not split when a side has fewer than three photos`() {
        val three = burst("13:00", "13:20", 3, uploaders = setOf(1L))
        val twoLater = burst("13:30", "13:40", 2, uploaders = setOf(2L))
        val twoEarlier = burst("12:00", "12:10", 2, uploaders = setOf(3L))

        assertThat(clusterer.cluster(three + twoLater)).hasSize(1)
        assertThat(clusterer.cluster(twoEarlier + three)).hasSize(1)
    }

    @Test
    fun `a photo uploaded together on the border keeps the day together`() {
        val before = burst("13:00", "13:20", 3, uploaders = setOf(1L))
        val together = photo("13:30", uploaders = setOf(1L, 2L))
        val after = burst("13:40", "14:00", 3, uploaders = setOf(2L))

        assertThat(clusterer.cluster(before + together + after)).hasSize(1)
    }

    @Test
    fun `the longest gap decides when a gap and a member split are both possible`() {
        val first = burst("08:00", "08:20", 3, uploaders = setOf(1L))
        val second = burst("08:30", "08:40", 2, uploaders = setOf(2L))
        val third = burst("13:00", "13:20", 3, uploaders = setOf(2L))

        // Cutting the 4 hour gap first leaves only two photos behind the member split, so that one is not cut.
        // Cutting the short member split first would give a different result.
        val clusters = clusterer.cluster(first + second + third)

        assertThat(groups(clusters)).containsExactly(ids(first + second), ids(third))
    }

    @Test
    fun `photos without a time do not take part in the member comparison`() {
        val first = burst("13:00", "13:20", 3, uploaders = setOf(1L))
        val second = burst("13:30", "13:50", 3, uploaders = setOf(2L))
        val untimed = photo(null, uploaders = setOf(1L, 2L))

        assertThat(clusterer.cluster(first + second + untimed)).hasSize(2)
    }

    @Test
    fun `a day where no photo has a time is one cluster`() {
        val photos = listOf(photo(null), photo(null), photo(null))

        val clusters = clusterer.cluster(photos)

        assertThat(groups(clusters)).containsExactly(ids(photos))
    }

    @Test
    fun `photos before and after midnight form one cluster that spans two days`() {
        val late = burst("23:10", "23:50", 3)
        val early = burst("00:20", "01:30", 3, nextDay)

        val cluster = clusterer.cluster(late + early).single()

        assertThat(cluster.photoIds).containsExactlyElementsOf((late + early).map { it.photoId })
        assertThat(cluster.startDate).isEqualTo(day)
        assertThat(cluster.endDate).isEqualTo(nextDay)
    }

    @Test
    fun `a gap of two hours across midnight is joined`() {
        val late = burst("23:00", "23:30", 3)
        val early = burst("01:30", "02:30", 3, nextDay)

        assertThat(clusterer.cluster(late + early)).hasSize(1)
    }

    @Test
    fun `a gap of two hours and one minute across midnight is not joined`() {
        val late = burst("23:00", "23:29", 3)
        val early = burst("01:30", "02:30", 3, nextDay)

        assertThat(clusterer.cluster(late + early)).hasSize(2)
    }

    @Test
    fun `joining across midnight does not merge the whole two days`() {
        val afternoon = burst("13:00", "15:00", 3)
        val late = burst("23:10", "23:50", 3)
        val early = burst("00:20", "01:30", 3, nextDay)
        val morning = burst("10:00", "12:00", 3, nextDay)

        val clusters = clusterer.cluster(afternoon + late + early + morning)

        assertThat(groups(clusters)).containsExactly(ids(afternoon), ids(late + early), ids(morning))
    }

    @Test
    fun `a cluster is joined across midnight only once`() {
        val first = burst("22:30", "23:50", 3)
        val steady = listOf("00:20", "03:00", "06:00", "09:00", "12:00", "15:00", "18:00", "21:00", "23:30")
            .map { photo(it, nextDay) }
        val third = burst("00:10", "01:00", 3, day.plusDays(2))

        val clusters = clusterer.cluster(first + steady + third)

        assertThat(groups(clusters)).containsExactly(ids(first + steady), ids(third))
    }

    @Test
    fun `a photo without a time joins the only cluster of its day`() {
        val timed = burst("13:00", "15:00", 6)
        val untimed = photo(null)

        val cluster = clusterer.cluster(timed + untimed).single()

        assertThat(cluster.photoIds).containsExactlyElementsOf(timed.map { it.photoId } + untimed.photoId)
    }

    @Test
    fun `a photo without a time joins the cluster of its upload batch`() {
        val batch = UUID.randomUUID()
        val morning = burst("09:00", "10:00", 3, uploaders = setOf(1L))
        val evening = burst("19:00", "20:00", 3, uploaders = setOf(2L))
        val mateInMorning = morning[0].copy(batchIds = setOf(batch))
        val matesInEvening = evening.take(2).map { it.copy(batchIds = setOf(batch)) }
        val untimed = photo(null, uploaders = setOf(9L), batches = setOf(batch))

        val clusters = clusterer.cluster(listOf(mateInMorning) + morning.drop(1) + matesInEvening + evening.drop(2) +
            untimed)

        assertThat(clusterOf(clusters, untimed).photoIds).contains(evening.last().photoId)
        assertThat(clusterOf(clusters, untimed).photoIds).doesNotContain(morning.first().photoId)
    }

    @Test
    fun `equal evidence from the batch falls back to the uploader`() {
        val batch = UUID.randomUUID()
        val morning = burst("09:00", "10:00", 3, uploaders = setOf(1L))
        val evening = burst("19:00", "20:00", 3, uploaders = setOf(7L))
        val mateInMorning = morning[0].copy(batchIds = setOf(batch))
        val mateInEvening = evening[0].copy(batchIds = setOf(batch))
        val untimed = photo(null, uploaders = setOf(7L), batches = setOf(batch))

        val clusters = clusterer.cluster(
            listOf(mateInMorning) + morning.drop(1) + listOf(mateInEvening) + evening.drop(1) + untimed
        )

        assertThat(clusterOf(clusters, untimed).photoIds).contains(evening.first().photoId)
        assertThat(clusterOf(clusters, untimed).photoIds).doesNotContain(morning.first().photoId)
    }

    @Test
    fun `an uploader who posted in two clusters falls back to the larger cluster`() {
        val larger = burst("09:00", "10:00", 4, uploaders = setOf(1L))
        val smaller = burst("19:00", "20:00", 3, uploaders = setOf(1L))
        val untimed = photo(null, uploaders = setOf(1L))

        val clusters = clusterer.cluster(larger + smaller + untimed)

        assertThat(clusterOf(clusters, untimed).photoIds).contains(larger.first().photoId)
    }

    @Test
    fun `an uploader who posted in one cluster only decides the cluster`() {
        val larger = burst("09:00", "10:00", 4, uploaders = setOf(1L))
        val smaller = burst("19:00", "20:00", 3, uploaders = setOf(2L))
        val untimed = photo(null, uploaders = setOf(2L))

        val clusters = clusterer.cluster(larger + smaller + untimed)

        assertThat(clusterOf(clusters, untimed).photoIds).contains(smaller.first().photoId)
    }

    @Test
    fun `equal sizes go to the cluster that starts earlier`() {
        val earlier = burst("09:00", "10:00", 3, uploaders = setOf(1L))
        val later = burst("19:00", "20:00", 3, uploaders = setOf(1L))
        val untimed = photo(null, uploaders = setOf(1L))

        val clusters = clusterer.cluster(later + earlier + untimed)

        assertThat(clusterOf(clusters, untimed).photoIds).contains(earlier.first().photoId)
    }

    @Test
    fun `a batch mate on another day does not decide the cluster`() {
        val batch = UUID.randomUUID()
        val larger = burst("09:00", "10:00", 4, uploaders = setOf(1L))
        val smaller = burst("19:00", "20:00", 3, uploaders = setOf(1L))
        val otherDay = photo("12:00", day.plusDays(3), batches = setOf(batch))
        val untimed = photo(null, uploaders = setOf(1L), batches = setOf(batch))

        val clusters = clusterer.cluster(larger + smaller + otherDay + untimed)

        assertThat(clusterOf(clusters, untimed).photoIds).contains(larger.first().photoId)
        assertThat(clusterOf(clusters, otherDay).photoIds).doesNotContain(untimed.photoId)
    }

    @Test
    fun `photos without a time are attached to the day they belong to`() {
        val first = burst("13:00", "15:00", 6)
        val second = burst("13:00", "15:00", 6, nextDay.plusDays(1))
        val onSecondDay = photo(null, nextDay.plusDays(1))

        val clusters = clusterer.cluster(first + second + onSecondDay)

        assertThat(clusterOf(clusters, onSecondDay).photoIds).contains(second.first().photoId)
        assertThat(clusterOf(clusters, first.first()).photoIds).doesNotContain(onSecondDay.photoId)
    }

    @Test
    fun `a cluster of five photos does not qualify`() {
        val photos = burst("13:00", "14:00", 5)

        assertThat(clusterer.cluster(photos).single().qualifies).isFalse()
    }

    @Test
    fun `a cluster with one uploader does not qualify`() {
        val photos = burst("13:00", "14:00", 6, uploaders = setOf(1L))

        assertThat(clusterer.cluster(photos).single().qualifies).isFalse()
    }

    @Test
    fun `six photos from two uploaders qualify`() {
        // The uploaders take turns. Members who stay apart would be split into two clusters (4.2.7 detail 2).
        val photos = listOf("13:00" to 1L, "13:10" to 2L, "13:20" to 1L, "13:30" to 2L, "13:40" to 1L, "13:50" to 2L)
            .map { (time, uploader) -> photo(time, uploaders = setOf(uploader)) }

        assertThat(clusterer.cluster(photos).single().qualifies).isTrue()
    }

    @Test
    fun `a photo uploaded together counts once and all its uploaders count`() {
        val alone = burst("13:00", "14:00", 5, uploaders = setOf(1L))
        val together = photo("14:30", uploaders = setOf(1L, 2L))

        val cluster = clusterer.cluster(alone + together).single()

        assertThat(cluster.photoCount).isEqualTo(6)
        assertThat(cluster.uploaderIds).containsExactlyInAnyOrder(1L, 2L)
        assertThat(cluster.qualifies).isTrue()
    }

    @Test
    fun `the settings can change the numbers`() {
        val photos = burst("13:00", "14:00", 5)
        val relaxed = PhotoClusterer(RecommendationSettings(minPhotos = 5))

        assertThat(relaxed.cluster(photos).single().qualifies).isTrue()
        assertThat(clusterer.cluster(photos).single().qualifies).isFalse()
    }

    @Test
    fun `photos of different days are different clusters`() {
        val first = burst("13:00", "15:00", 6)
        val second = burst("13:00", "15:00", 6, day.plusDays(3))

        val clusters = clusterer.cluster(second + first)

        assertThat(groups(clusters)).containsExactly(ids(first), ids(second))
        assertThat(clusters.map { it.startDate }).containsExactly(day, day.plusDays(3))
        assertThat(clusters.map { it.endDate }).containsOnlyNulls()
    }

    @Test
    fun `photo IDs follow the order of a recommended bundle`() {
        val firstLate = photo("23:10")
        val secondLate = photo("23:40")
        val untimedFirstDay = photo(null)
        val firstEarly = photo("00:10", nextDay)
        val secondEarly = photo("00:50", nextDay)
        val untimedNextDay = photo(null, nextDay)

        val cluster = clusterer.cluster(
            listOf(untimedNextDay, secondEarly, untimedFirstDay, firstEarly, secondLate, firstLate)
        ).single()

        assertThat(cluster.photoIds).containsExactly(
            firstLate.photoId,
            secondLate.photoId,
            untimedFirstDay.photoId,
            firstEarly.photoId,
            secondEarly.photoId,
            untimedNextDay.photoId
        )
    }

    @Test
    fun `no photos give no clusters`() {
        assertThat(clusterer.cluster(emptyList())).isEmpty()
    }

    @Test
    fun `the default settings are the numbers of the specification`() {
        val settings = RecommendationSettings()

        assertThat(settings.splitGap).isEqualTo(Duration.ofHours(4))
        assertThat(settings.minPhotosOnEachSide).isEqualTo(3)
        assertThat(settings.midnightFrom).isEqualTo(LocalTime.of(22, 0))
        assertThat(settings.midnightUntil).isEqualTo(LocalTime.of(3, 0))
        assertThat(settings.midnightGap).isEqualTo(Duration.ofHours(2))
        assertThat(settings.minPhotos).isEqualTo(6)
        assertThat(settings.minUploaders).isEqualTo(2)
    }

    private fun photo(
        time: String?,
        date: LocalDate = day,
        uploaders: Set<Long> = setOf(1L, 2L),
        batches: Set<UUID> = emptySet()
    ) = PhotoSample(nextId++, date, time?.let(LocalTime::parse), uploaders, batches)

    // Evenly spaced from the first to the last time, both included.
    private fun burst(
        from: String,
        to: String,
        count: Int,
        date: LocalDate = day,
        uploaders: Set<Long> = setOf(1L, 2L)
    ): List<PhotoSample> {
        val start = LocalTime.parse(from).toSecondOfDay()
        val end = LocalTime.parse(to).toSecondOfDay()
        return (0 until count).map { index ->
            val second = if (count == 1) start else start + (end - start) * index / (count - 1)
            PhotoSample(nextId++, date, LocalTime.ofSecondOfDay(second.toLong()), uploaders)
        }
    }

    private fun ids(photos: List<PhotoSample>): Set<Long> = photos.map { it.photoId }.toSet()

    private fun groups(clusters: List<PhotoCluster>): List<Set<Long>> = clusters.map { it.photoIds.toSet() }

    private fun clusterOf(clusters: List<PhotoCluster>, photo: PhotoSample): PhotoCluster =
        clusters.single { photo.photoId in it.photoIds }
}
