package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.AiAnalysis
import com.bunch.recommendation.domain.combination.BundleEligibility
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.PreparedPhoto
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The photos that count for a bundle and the size a bundle needs (recommended bundle specification 3-1, 5-1). */
class BundleEligibilityTest {
    private val eligibility = BundleEligibility()

    private fun prepared(vararg photos: RecoPhoto) = ready(photos.toList())

    private fun usable(photo: RecoPhoto): Boolean = eligibility.isUsable(prepared(photo).photos.single())

    private fun sixPhotos(members: List<Long>): List<RecoPhoto> =
        (1..6).map { photo(it.toLong(), time = "10:0$it", members = listOf(members[it % members.size])) }

    private fun enough(photos: List<RecoPhoto>): Boolean {
        val day = prepared(*photos.toTypedArray())
        return eligibility.isEnough(day.photos, day.duplicateGroups)
    }

    @Test
    fun `a photo taken with the camera, not low and not a document is usable`() {
        assertThat(usable(photo(1, time = "10:00"))).isTrue()
        assertThat(usable(photo(1, time = "10:00").copy(quality = null))).isTrue()
        assertThat(usable(photo(1, time = "10:00").copy(quality = PhotoQuality.NORMAL))).isTrue()
    }

    @Test
    fun `a screenshot, a saved image, a photo of low quality and a document are not usable`() {
        assertThat(usable(photo(1, time = "10:00").copy(source = SourceType.SCREENSHOT))).isFalse()
        assertThat(usable(photo(1, time = "10:00").copy(source = SourceType.SAVED))).isFalse()
        assertThat(usable(photo(1, time = "10:00").copy(quality = PhotoQuality.LOW))).isFalse()
        assertThat(usable(photo(1, time = "10:00", analysis = AiAnalysis(subject = PhotoSubject.DOCUMENT)))).isFalse()
    }

    @Test
    fun `a photo of which the subject is not known is not a document`() {
        assertThat(usable(photo(1, time = "10:00", analysis = AiAnalysis.NONE))).isTrue()
    }

    @Test
    fun `the photos are counted by their groups of duplicates and the members by the uploaders of those groups`() {
        val day = prepared(
            // The same original saved again by another member (R1): the same moment, model and size.
            photo(1, time = "10:00:00", members = listOf(1), millis = 5, model = "iPhone15,2", size = 4032 to 3024),
            photo(2, time = "10:00:00", members = listOf(2), millis = 5, model = "iPhone15,2", size = 4032 to 3024),
            photo(3, time = "11:00:00", members = listOf(1))
        )

        assertThat(eligibility.photoCount(day.photos, day.duplicateGroups)).isEqualTo(2)
        assertThat(eligibility.uploaderCount(day.photos, day.duplicateGroups)).isEqualTo(2)
    }

    @Test
    fun `an unusable photo is not counted, and a group of only unusable photos is not either`() {
        val day = prepared(
            photo(1, time = "10:00", members = listOf(1)),
            photo(2, time = "10:10", members = listOf(2)).copy(quality = PhotoQuality.LOW),
            photo(3, time = "10:20", members = listOf(3)).copy(source = SourceType.SCREENSHOT)
        )

        assertThat(eligibility.photoCount(day.photos, day.duplicateGroups)).isEqualTo(1)
        assertThat(eligibility.uploaderCount(day.photos, day.duplicateGroups)).isEqualTo(1)
    }

    @Test
    fun `a photo several members uploaded counts all of them`() {
        val day = prepared(photo(1, time = "10:00", members = listOf(1, 2, 3)))

        assertThat(eligibility.uploaderCount(day.photos, day.duplicateGroups)).isEqualTo(3)
    }

    @Test
    fun `a bundle needs six photos and two members`() {
        assertThat(enough(sixPhotos(listOf(1, 2)))).isTrue()
        assertThat(enough(sixPhotos(listOf(1, 2)).take(5))).isFalse()
        assertThat(enough(sixPhotos(listOf(1)))).isFalse()
    }

    @Test
    fun `six photos that are one picture are not six`() {
        val burst = (1..6).map {
            photo(
                it.toLong(), time = "10:00:00", members = listOf(1L + it % 2),
                millis = 5, model = "iPhone15,2", size = 4032 to 3024
            )
        }

        assertThat(enough(burst)).isFalse()
    }

    @Test
    fun `photos that are given by their prepared form count the same`() {
        val day = prepared(*sixPhotos(listOf(1, 2)).toTypedArray())
        val photos: Collection<PreparedPhoto> = day.photos

        assertThat(eligibility.photoCount(photos, day.duplicateGroups)).isEqualTo(6)
    }
}
