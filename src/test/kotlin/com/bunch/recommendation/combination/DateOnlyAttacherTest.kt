package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CombinerTestSupport.day
import com.bunch.recommendation.combination.CombinerTestSupport.meals
import com.bunch.recommendation.combination.CombinerTestSupport.photoIds
import com.bunch.recommendation.combination.CombinerTestSupport.shot
import com.bunch.recommendation.domain.combination.DateOnlyAttacher
import com.bunch.recommendation.domain.combination.GeneralBundleFinder
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The photos with a date only that are given to a bundle (recommended bundle specification 7-9, 7-10). */
class DateOnlyAttacherTest {
    private val batchA = UUID.nameUUIDFromBytes("a".toByteArray())
    private val batchB = UUID.nameUUIDFromBytes("b".toByteArray())

    // Two bundles: a lunch of 8 photos from 10:00 and a dinner of 8 photos from 18:00. The first 3 photos of the lunch
    // and the first 5 of the dinner were uploaded in the batch A, and the last 2 of each in the batch B.
    private fun twoMeals(): List<RecoPhoto> =
        meals(1, "10:00", 8, batch = { if (it < 3) batchA else if (it >= 6) batchB else null }) +
            meals(101, "18:00", 8, batch = { if (it < 5) batchA else if (it >= 6) batchB else null })

    // The general bundles of the photos, and what the attacher makes of them.
    private fun attach(photos: List<RecoPhoto>, vararg dateOnly: RecoPhoto): List<RecoBundle> {
        val ready = day(photos + dateOnly)
        return DateOnlyAttacher().attach(ready, GeneralBundleFinder().find(ready).bundles)
    }

    private fun attached(bundle: RecoBundle) =
        bundle.photos.filter { it.role == PhotoRole.ATTACHED_DATE_ONLY }.map { it.photoId }

    @Test
    fun `a photo goes to the bundle that most of the photos of its batch are in`() {
        val result = attach(twoMeals(), shot(900, null, batch = batchA))

        assertThat(attached(result[0])).isEmpty()
        assertThat(attached(result[1])).containsExactly(900L)
    }

    @Test
    fun `when the bundles have as many of the batch it goes to the one that starts first`() {
        val result = attach(twoMeals(), shot(900, null, batch = batchB))

        assertThat(attached(result[0])).containsExactly(900L)
        assertThat(attached(result[1])).isEmpty()
    }

    @Test
    fun `a photo whose batch tells nothing goes to the bundle of the day when there is only one`() {
        val result = attach(meals(1, "10:00", 8), shot(900, null))

        assertThat(result).hasSize(1)
        assertThat(attached(result.single())).containsExactly(900L)
    }

    @Test
    fun `and to no bundle when there are two`() {
        val result = attach(twoMeals(), shot(900, null))

        assertThat(result.flatMap { attached(it) }).isEmpty()
    }

    @Test
    fun `photos that were given a date together are of one batch`() {
        val lunch = meals(1, "10:00", 8)
        val photos = listOf(lunch.first().copy(dateBatchId = batchA)) + lunch.drop(1) + meals(101, "18:00", 8)

        val result = attach(photos, shot(900, null).copy(dateBatchId = batchA))

        assertThat(attached(result[0])).containsExactly(900L)
        assertThat(attached(result[1])).isEmpty()
    }

    @Test
    fun `the photos that came are counted in the bundle, the ones that are not usable are not`() {
        val extra = arrayOf(shot(900, null), shot(901, null, source = SourceType.SCREENSHOT))

        val result = attach(meals(1, "10:00", 8), *extra).single()

        assertThat(attached(result)).containsExactly(900L, 901L)
        assertThat(result.photoCount).isEqualTo(9)
        assertThat(result.trace.counts.photos).isEqualTo(9)
        assertThat(result.photos).hasSize(10)
    }

    @Test
    fun `a bundle that did not stand is not made to stand by the photos that come`() {
        assertThat(attach(meals(1, "10:00", 5), shot(900, null))).isEmpty()
    }

    @Test
    fun `the time of the bundle stays, the photos have none`() {
        val before = attach(meals(1, "10:00", 8)).single()
        val after = attach(meals(1, "10:00", 8), shot(900, null)).single()

        assertThat(after.startAt).isEqualTo(before.startAt)
        assertThat(after.endAt).isEqualTo(before.endAt)
        assertThat(photoIds(after)).contains(900L)
    }

    @Test
    fun `a day with no photo with a date only gives the bundles back as they are`() {
        val photos = twoMeals()
        val ready = day(photos)
        val bundles = GeneralBundleFinder().find(ready).bundles

        assertThat(DateOnlyAttacher().attach(ready, bundles)).isEqualTo(bundles)
    }
}
