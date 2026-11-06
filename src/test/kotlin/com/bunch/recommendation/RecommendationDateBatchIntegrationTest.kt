package com.bunch.recommendation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Photos that were given a date in one request are one batch, like the photos that were uploaded together
 * (4.2.7 rule 3). A photo without a time is put in the cluster of the photos with a time it shares a batch with
 * (4.2.7 detail 4, first).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationDateBatchIntegrationTest : RecommendationIntegrationSupport() {
    @Test
    fun `the material of a photo dated in a batch has its date batch next to its upload batch`() {
        val (owner, bunchId) = bunchWithOwner()
        val dated = insertPhoto(bunchId, gatheringDay, "13:00:00", dateSource = "USER", uploaders = listOf(owner))
        val plain = insertPhoto(bunchId, gatheringDay, "14:00:00", uploaders = listOf(owner))
        val dateBatch = UUID.randomUUID()
        jdbc.update("UPDATE photos SET date_batch_id = CAST(? AS uuid) WHERE id = ?", dateBatch.toString(), dated)

        val material = recommendation.materialPhotos(bunchId).associateBy { it.photoId }

        assertThat(material.getValue(dated).batchIds.toList()).hasSize(2).contains(dateBatch)
        assertThat(material.getValue(plain).batchIds.toList()).hasSize(1)
    }

    @Test
    fun `photos dated together follow the photo with a time they were dated with`() {
        val scene = scene()

        dateTogether(scene, listOf(scene.late, scene.firstWithoutTime, scene.secondWithoutTime))
        recalculate(scene.bunchId, gatheringDay)

        val clusters = clustersOf(scene.bunchId)
        assertThat(clusters).containsExactlyInAnyOrder(
            scene.afternoon.toSet(),
            (scene.evening + scene.late + scene.firstWithoutTime + scene.secondWithoutTime).toSet()
        )
    }

    @Test
    fun `photos dated in different requests do not follow each other`() {
        val scene = scene()

        dateTogether(scene, listOf(scene.firstWithoutTime, scene.secondWithoutTime))
        dateTogether(scene, listOf(scene.late))
        recalculate(scene.bunchId, gatheringDay)

        val clusters = clustersOf(scene.bunchId)
        assertThat(clusters).containsExactlyInAnyOrder(
            (scene.afternoon + scene.firstWithoutTime + scene.secondWithoutTime).toSet(),
            (scene.evening + scene.late).toSet()
        )
    }

    // An afternoon of eight photos and an evening of six, both by the two members, and three photos that are not on
    // the day yet: one with a time on the next day and two without a date. Without a sign the photos without a time
    // go to the afternoon, which has the most photos.
    private fun scene(): Scene {
        val (owner, bunchId) = bunchWithOwner()
        val second = joined(owner, bunchId)
        val members = listOf(owner, second)
        return Scene(
            bunchId = bunchId,
            owner = owner,
            afternoon = shoot(bunchId, gatheringDay, "13:00", 8, members, stepMinutes = 15),
            evening = shoot(bunchId, gatheringDay, "20:00", 6, members, stepMinutes = 30),
            late = insertPhoto(bunchId, "2026-10-20", "21:00:00", uploaders = listOf(owner)),
            firstWithoutTime = insertPhoto(bunchId, null, uploaders = listOf(owner)),
            secondWithoutTime = insertPhoto(bunchId, null, uploaders = listOf(second))
        )
    }

    private fun dateTogether(scene: Scene, photoIds: List<Long>) {
        setPhotoDate(scene.owner, scene.bunchId, photoIds, gatheringDay).andExpect(status().isOk)
    }

    private fun clustersOf(bunchId: Long): List<Set<Long>> =
        candidates(bunchId).map { candidate -> photosOf(candidate.id).map { it.first }.toSet() }

    private data class Scene(
        val bunchId: Long,
        val owner: Long,
        val afternoon: List<Long>,
        val evening: List<Long>,
        val late: Long,
        val firstWithoutTime: Long,
        val secondWithoutTime: Long
    )
}
