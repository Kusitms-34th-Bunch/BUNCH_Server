package com.bunch.recommendation

import com.bunch.recommendation.domain.PhotoCluster
import com.bunch.recommendation.domain.PhotoSample
import com.bunch.recommendation.domain.TitleContext
import com.bunch.recommendation.domain.TitleContextBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class TitleContextBuilderTest {
    private val builder = TitleContextBuilder()
    private val day = LocalDate.of(2026, 10, 19)
    private val nicknames = mapOf(1L to "멤버 A", 2L to "멤버 B", 3L to "멤버 C")

    @Test
    fun `the nicknames start with the member who uploaded the most photos of the cluster`() {
        val photos = listOf(
            photo(1, uploaders = setOf(2L)),
            photo(2, uploaders = setOf(1L)),
            photo(3, uploaders = setOf(1L)),
            photo(4, uploaders = setOf(1L)),
            photo(5, uploaders = setOf(3L)),
            photo(6, uploaders = setOf(3L))
        )

        val context = build(clusterOf(photos), photos)

        assertThat(context.uploaderNicknames).containsExactly("멤버 A", "멤버 C", "멤버 B")
    }

    @Test
    fun `members with as many photos are in the order of their member number`() {
        val photos = listOf(
            photo(1, uploaders = setOf(3L)),
            photo(2, uploaders = setOf(1L)),
            photo(3, uploaders = setOf(2L))
        )

        val context = build(clusterOf(photos), photos)

        assertThat(context.uploaderNicknames).containsExactly("멤버 A", "멤버 B", "멤버 C")
    }

    @Test
    fun `a photo uploaded together counts for every member who uploaded it`() {
        val photos = listOf(
            photo(1, uploaders = setOf(1L, 2L)),
            photo(2, uploaders = setOf(2L)),
            photo(3, uploaders = setOf(3L))
        )

        val context = build(clusterOf(photos), photos)

        assertThat(context.uploaderNicknames).containsExactly("멤버 B", "멤버 A", "멤버 C")
    }

    @Test
    fun `a member without a nickname is left out`() {
        val photos = listOf(
            photo(1, uploaders = setOf(1L)),
            photo(2, uploaders = setOf(2L)),
            photo(3, uploaders = setOf(9L))
        )

        val context = build(clusterOf(photos), photos, nicknames = mapOf(1L to "멤버 A", 2L to " "))

        assertThat(context.uploaderNicknames).containsExactly("멤버 A")
    }

    @Test
    fun `the shooting times are only of the photos that have one`() {
        val photos = listOf(
            photo(1, time = LocalTime.of(19, 0)),
            photo(2, time = null),
            photo(3, time = LocalTime.of(21, 30))
        )

        val context = build(clusterOf(photos), photos)

        assertThat(context.photoTimes).containsExactly(LocalTime.of(19, 0), LocalTime.of(21, 30))
    }

    @Test
    fun `the dates of the cluster and what is passed on are kept`() {
        val photos = listOf(photo(1), photo(2))
        val cluster = clusterOf(photos, endDate = day.plusDays(1))

        val context = build(
            cluster = cluster,
            photos = photos,
            bunchMemberCount = 4,
            visibleTitles = setOf("불타는 토요일 밤"),
            recentSentenceIds = listOf("default.weekend_evening.1", "default.monday.1")
        )

        assertThat(context.startDate).isEqualTo(day)
        assertThat(context.endDate).isEqualTo(day.plusDays(1))
        assertThat(context.bunchMemberCount).isEqualTo(4)
        assertThat(context.visibleTitles).containsExactly("불타는 토요일 밤")
        assertThat(context.recentSentenceIds).containsExactly("default.weekend_evening.1", "default.monday.1")
    }

    @Test
    fun `the earliest photo is taken from every dated photo of the bunch`() {
        val photos = listOf(photo(1), photo(2))
        val dated = photos + photo(50, date = day.minusDays(90)) + photo(51, date = day.minusDays(10))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.earliestPhotoDate).isEqualTo(day.minusDays(90))
    }

    @Test
    fun `the earliest photo is the first day of the cluster when there is nothing else`() {
        val photos = listOf(photo(1), photo(2))

        val context = build(clusterOf(photos), photos, datedPhotos = emptyList())

        assertThat(context.earliestPhotoDate).isEqualTo(day)
    }

    @Test
    fun `the previous end is the latest date before the cluster`() {
        val photos = listOf(photo(1), photo(2))
        val dated = photos + photo(50, date = day.minusDays(40)) + photo(51, date = day.minusDays(3))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.previousEndDate).isEqualTo(day.minusDays(3))
    }

    @Test
    fun `there is no previous end when nothing was taken before the cluster`() {
        val photos = listOf(photo(1), photo(2))
        val dated = photos + photo(50, date = day.plusDays(5))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.previousEndDate).isNull()
    }

    @Test
    fun `the photos of the cluster itself are not the previous end`() {
        val photos = listOf(photo(1, date = day.minusDays(1), time = LocalTime.of(23, 30)), photo(2, date = day))
        val cluster = clusterOf(photos, startDate = day.minusDays(1), endDate = day)

        val context = build(cluster, photos, datedPhotos = photos)

        assertThat(context.previousEndDate).isNull()
    }

    @Test
    fun `a photo of the same day before the first shooting time is the previous end`() {
        val photos = listOf(photo(1, time = LocalTime.of(19, 0)), photo(2, time = LocalTime.of(20, 0)))
        val dated = photos + photo(50, time = LocalTime.of(13, 0)) + photo(51, date = day.minusDays(60))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.previousEndDate).isEqualTo(day)
    }

    @Test
    fun `a photo of the same day after the first shooting time is not the previous end`() {
        val photos = listOf(photo(1, time = LocalTime.of(13, 0)), photo(2, time = LocalTime.of(14, 0)))
        val dated = photos + photo(50, time = LocalTime.of(21, 0)) + photo(51, date = day.minusDays(60))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.previousEndDate).isEqualTo(day.minusDays(60))
    }

    @Test
    fun `a photo of the same day without a time cannot be placed and is not the previous end`() {
        val photos = listOf(photo(1, time = LocalTime.of(19, 0)), photo(2, time = LocalTime.of(20, 0)))
        val dated = photos + photo(50, time = null) + photo(51, date = day.minusDays(60))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.previousEndDate).isEqualTo(day.minusDays(60))
    }

    @Test
    fun `a cluster without a shooting time takes no photo of its own day as the previous end`() {
        val photos = listOf(photo(1, time = null), photo(2, time = null))
        val dated = photos + photo(50, time = LocalTime.of(9, 0)) + photo(51, date = day.minusDays(60))

        val context = build(clusterOf(photos), photos, datedPhotos = dated)

        assertThat(context.previousEndDate).isEqualTo(day.minusDays(60))
    }

    private fun photo(
        id: Long,
        date: LocalDate = day,
        time: LocalTime? = LocalTime.of(20, 0),
        uploaders: Set<Long> = setOf(1L)
    ) = PhotoSample(photoId = id, capturedDate = date, capturedTime = time, uploaderIds = uploaders)

    private fun clusterOf(
        photos: List<PhotoSample>,
        startDate: LocalDate = day,
        endDate: LocalDate? = null
    ) = PhotoCluster(
        photoIds = photos.map { it.photoId },
        startDate = startDate,
        endDate = endDate,
        uploaderIds = photos.flatMap { it.uploaderIds }.toSet(),
        qualifies = true
    )

    private fun build(
        cluster: PhotoCluster,
        photos: List<PhotoSample>,
        nicknames: Map<Long, String> = this.nicknames,
        bunchMemberCount: Int = 3,
        datedPhotos: List<PhotoSample> = photos,
        visibleTitles: Set<String> = emptySet(),
        recentSentenceIds: List<String> = emptyList()
    ): TitleContext = builder.build(
        cluster = cluster,
        nicknames = nicknames,
        bunchMemberCount = bunchMemberCount,
        datedPhotos = datedPhotos,
        visibleTitles = visibleTitles,
        recentSentenceIds = recentSentenceIds
    )
}
