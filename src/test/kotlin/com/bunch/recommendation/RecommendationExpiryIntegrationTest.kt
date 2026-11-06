package com.bunch.recommendation

import com.bunch.recommendation.service.RecommendationScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationExpiryIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var scheduler: RecommendationScheduler

    // The run of November 6 at 04:30. Its cutoff is the start of October 7 in Korean time.
    private val now = kst("2026-11-06T04:30:00")

    @Test
    fun `a bundle whose last photo was added more than thirty days ago is deleted with its photos`() {
        val bunchId = calculatedBunch().bunchId
        setLastPhotoAddedAt(candidates(bunchId).single().id, kst("2026-10-06T23:59:00"))

        assertThat(scheduler.runExpiry(now)).isEqualTo(1)

        assertThat(candidates(bunchId)).isEmpty()
        assertThat(count("candidate_photos")).isZero()
    }

    @Test
    fun `the photos of the photo box stay when a bundle is deleted`() {
        val bunchId = calculatedBunch().bunchId
        setLastPhotoAddedAt(candidates(bunchId).single().id, kst("2026-09-01T10:00:00"))

        scheduler.runExpiry(now)

        assertThat(count("photos")).isEqualTo(8)
        assertThat(count("photo_uploaders")).isEqualTo(8)
    }

    @Test
    fun `a bundle stays through the thirtieth gatheringDay and goes the gatheringDay after`() {
        val bunchId = calculatedBunch().bunchId
        val candidate = candidates(bunchId).single()

        setLastPhotoAddedAt(candidate.id, kst("2026-10-07T00:00:00"))
        assertThat(scheduler.runExpiry(now)).isZero()
        assertThat(candidates(bunchId)).hasSize(1)

        setLastPhotoAddedAt(candidate.id, kst("2026-10-06T23:59:59"))
        assertThat(scheduler.runExpiry(now)).isEqualTo(1)
        assertThat(candidates(bunchId)).isEmpty()
    }

    @Test
    fun `running it again changes nothing`() {
        val gathering = bunchWithGathering()
        val bunchId = gathering.bunchId
        shoot(bunchId, "2026-10-25", "19:00", 8, gathering.members)
        recalculate(bunchId, gatheringDay, "2026-10-25")
        val (old, fresh) = candidates(bunchId)
        setLastPhotoAddedAt(old.id, kst("2026-09-01T10:00:00"))
        setLastPhotoAddedAt(fresh.id, kst("2026-10-20T10:00:00"))

        assertThat(scheduler.runExpiry(now)).isEqualTo(1)
        assertThat(scheduler.runExpiry(now)).isZero()
        assertThat(scheduler.runExpiry(now.plusSeconds(3600))).isZero()

        assertThat(candidates(bunchId).map { it.id }).containsExactly(fresh.id)
    }

    @Test
    fun `a bunch whose changes are waiting for their calculation is left`() {
        val bunchId = calculatedBunch().bunchId
        setLastPhotoAddedAt(candidates(bunchId).single().id, kst("2026-09-01T10:00:00"))
        jdbc.update(
            "INSERT INTO recommendation_dirty_dates (bunch_id, captured_date, changed_at) " +
                "VALUES (?, DATE '2026-10-19', now())",
            bunchId
        )

        assertThat(scheduler.runExpiry(now)).isZero()
        assertThat(candidates(bunchId)).hasSize(1)

        jdbc.update("DELETE FROM recommendation_dirty_dates WHERE bunch_id = ?", bunchId)

        assertThat(scheduler.runExpiry(now)).isEqualTo(1)
        assertThat(candidates(bunchId)).isEmpty()
    }

    @Test
    fun `a gathering whose photos were all added more than thirty days ago is not made a bundle`() {
        val gathering = bunchWithGathering()
        gathering.photoIds.forEach { setAddedAt(it, daysAgo(31).toString()) }

        recalculate(gathering.bunchId, gatheringDay)

        assertThat(candidates(gathering.bunchId)).isEmpty()
    }

    @Test
    fun `a gathering whose last photo was added within thirty days is made a bundle`() {
        val gathering = bunchWithGathering()
        val lastAdded = daysAgo(29)
        gathering.photoIds.forEach { setAddedAt(it, daysAgo(40).toString()) }
        setAddedAt(gathering.photoIds.last(), lastAdded.toString())

        recalculate(gathering.bunchId, gatheringDay)

        val candidate = candidates(gathering.bunchId).single()
        assertThat(candidate.lastPhotoAddedAt).isEqualTo(lastAdded)
        assertThat(photosOf(candidate.id)).hasSize(8)
    }

    @Test
    fun `a deleted bundle does not come back when its date is calculated again`() {
        val gathering = calculatedBunch()
        val bunchId = gathering.bunchId
        expireByTime(gathering)
        assertThat(scheduler.runExpiry()).isEqualTo(1)

        recalculate(bunchId, gatheringDay)
        recalculate(bunchId, "2026-10-18", "2026-10-20")

        assertThat(candidates(bunchId)).isEmpty()
    }

    @Test
    fun `a new photo brings a deleted bundle back as a new candidate with all its old photos`() {
        val gathering = calculatedBunch()
        val bunchId = gathering.bunchId
        expireByTime(gathering)
        scheduler.runExpiry()
        assertThat(candidates(bunchId)).isEmpty()

        insertPhoto(bunchId, gatheringDay, "19:05", uploaders = listOf(gathering.members.last()))
        recalculate(bunchId, gatheringDay)

        val back = candidates(bunchId).single()
        assertThat(photosOf(back.id)).hasSize(9)
        assertThat(back.lastPhotoAddedAt).isAfter(daysAgo(1))
    }

    @Test
    fun `only the expired bundle of the bunch is deleted and other bunches are not touched`() {
        val expired = calculatedBunch().bunchId
        val fresh = calculatedBunch().bunchId
        setLastPhotoAddedAt(candidates(expired).single().id, kst("2026-09-01T10:00:00"))
        setLastPhotoAddedAt(candidates(fresh).single().id, Instant.parse("2026-11-05T00:00:00Z"))

        assertThat(scheduler.runExpiry(now)).isEqualTo(1)

        assertThat(candidates(expired)).isEmpty()
        assertThat(candidates(fresh)).hasSize(1)
    }

    // A bunch whose gathering is already a recommended bundle.
    private fun calculatedBunch(): Gathering {
        val gathering = bunchWithGathering()
        recalculate(gathering.bunchId, gatheringDay)
        return gathering
    }

    // Makes the bundle and all its photos look as if their last photo was added 40 days ago.
    private fun expireByTime(gathering: Gathering) {
        gathering.photoIds.forEach { setAddedAt(it, daysAgo(40).toString()) }
        setLastPhotoAddedAt(candidates(gathering.bunchId).single().id, daysAgo(40))
    }
}
