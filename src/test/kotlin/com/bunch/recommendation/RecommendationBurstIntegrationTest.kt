package com.bunch.recommendation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationBurstIntegrationTest : RecommendationIntegrationSupport() {
    @Test
    fun `the material of a photo carries its image fingerprint`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val withValue = insertPhoto(bunchId, gatheringDay, "19:00:00", uploaders = listOf(ownerId))
        val withoutValue = insertPhoto(bunchId, gatheringDay, "19:10:00", uploaders = listOf(ownerId))
        fingerprint(withValue, 1234L)

        val material = recommendation.materialPhotos(bunchId).associateBy { it.photoId }

        assertThat(material.getValue(withValue).fingerprint).isEqualTo(1234L)
        assertThat(material.getValue(withoutValue).fingerprint).isNull()
    }

    @Test
    fun `a burst of one member is reduced to one photo of the card`() {
        val (bunchId, burst) = gatheringWithBurst(single = 5)
        burst.forEach { fingerprint(it, 0b1011L) }

        recalculate(bunchId, gatheringDay)

        assertThat(cardPhotoCount(bunchId)).isEqualTo(6)
    }

    @Test
    fun `the same photos without fingerprints are all kept`() {
        val (bunchId, _) = gatheringWithBurst(single = 5)

        recalculate(bunchId, gatheringDay)

        assertThat(cardPhotoCount(bunchId)).isEqualTo(7)
    }

    @Test
    fun `a burst is not a recommendation when fewer than six photos are left after it`() {
        val (bunchId, burst) = gatheringWithBurst(single = 4)
        burst.forEach { fingerprint(it, 0b1011L) }

        recalculate(bunchId, gatheringDay)

        assertThat(candidates(bunchId)).isEmpty()
    }

    @Test
    fun `shots a second apart that look different are not a burst`() {
        val (bunchId, burst) = gatheringWithBurst(single = 5)
        fingerprint(burst[0], 0L)
        fingerprint(burst[1], -1L)

        recalculate(bunchId, gatheringDay)

        assertThat(cardPhotoCount(bunchId)).isEqualTo(7)
    }

    // Two members take turns for [single] photos ten minutes apart, then the first member shoots twice a second apart.
    private fun gatheringWithBurst(single: Int): Pair<Long, List<Long>> {
        val (ownerId, bunchId) = bunchWithOwner()
        val second = joined(ownerId, bunchId)
        val members = listOf(ownerId, second)
        shoot(bunchId, gatheringDay, "19:00", single, members)
        val burst = listOf("19:55:00", "19:55:01").map {
            insertPhoto(bunchId, gatheringDay, it, uploaders = listOf(ownerId))
        }
        return bunchId to burst
    }

    // The photos chosen for the card of the only candidate of the bunch.
    private fun cardPhotoCount(bunchId: Long): Int =
        photosOf(candidates(bunchId).single().id).count { it.second != null }

    private fun fingerprint(photoId: Long, value: Long) {
        jdbc.update(
            "INSERT INTO photo_image_fingerprints (photo_id, fingerprint, created_at) VALUES (?, ?, now())",
            photoId,
            value
        )
    }
}
