package com.bunch.recommendation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationOrderIntegrationTest : RecommendationIntegrationSupport() {
    private val secondDay = "2026-10-21"
    private val thirdDay = "2026-10-23"

    @Test
    fun `the bundle whose last photo was added most recently comes first`() {
        val gathering = bunchWithGathering()
        val bunchId = gathering.bunchId
        shoot(bunchId, secondDay, "19:00", 8, gathering.members)
        shoot(bunchId, thirdDay, "19:00", 8, gathering.members)
        recalculate(bunchId, gatheringDay, secondDay, thirdDay)
        val ids = idsByDay(bunchId)
        setLastPhotoAddedAt(ids.getValue(gatheringDay), daysAgo(5))
        setLastPhotoAddedAt(ids.getValue(secondDay), daysAgo(1))
        setLastPhotoAddedAt(ids.getValue(thirdDay), daysAgo(3))

        val expected = listOf(ids.getValue(secondDay), ids.getValue(thirdDay), ids.getValue(gatheringDay))

        assertThat(orderedIds(bunchId)).isEqualTo(expected)
    }

    @Test
    fun `a bundle added a photo most recently comes first even with fewer members`() {
        val gathering = bunchWithGathering(memberCount = 3)
        val bunchId = gathering.bunchId
        shoot(bunchId, secondDay, "19:00", 8, gathering.members.take(2))
        recalculate(bunchId, gatheringDay, secondDay)
        val ids = idsByDay(bunchId)
        setLastPhotoAddedAt(ids.getValue(gatheringDay), daysAgo(4))
        setLastPhotoAddedAt(ids.getValue(secondDay), daysAgo(1))

        assertThat(orderedIds(bunchId)).containsExactly(ids.getValue(secondDay), ids.getValue(gatheringDay))
    }

    @Test
    fun `with the same time the bundle with more members comes first`() {
        val gathering = bunchWithGathering()
        val bunchId = gathering.bunchId
        shoot(bunchId, secondDay, "19:00", 8, gathering.members + joined(gathering.members.first(), bunchId))
        recalculate(bunchId, gatheringDay, secondDay)
        val ids = sameTime(bunchId)

        assertThat(orderedIds(bunchId)).containsExactly(ids.getValue(secondDay), ids.getValue(gatheringDay))
    }

    @Test
    fun `with the same time and members the bundle with more photos comes first`() {
        val gathering = bunchWithGathering(count = 6)
        val bunchId = gathering.bunchId
        shoot(bunchId, secondDay, "19:00", 8, gathering.members)
        recalculate(bunchId, gatheringDay, secondDay)
        val ids = sameTime(bunchId)

        assertThat(orderedIds(bunchId)).containsExactly(ids.getValue(secondDay), ids.getValue(gatheringDay))
    }

    @Test
    fun `the number of photos is the photos of the card and not all the photos of the gathering`() {
        val gathering = bunchWithGathering(count = 11)
        val bunchId = gathering.bunchId
        shoot(bunchId, secondDay, "10:00", 25, gathering.members)
        recalculate(bunchId, gatheringDay, secondDay)
        val ids = sameTime(bunchId)

        assertThat(photosOf(ids.getValue(gatheringDay)).count { it.second != null }).isEqualTo(10)
        assertThat(photosOf(ids.getValue(secondDay)).count { it.second != null }).isEqualTo(10)
        assertThat(orderedIds(bunchId)).containsExactly(ids.getValue(gatheringDay), ids.getValue(secondDay))
    }

    @Test
    fun `bundles that are the same in everything keep the order of their numbers`() {
        val gathering = bunchWithGathering()
        val bunchId = gathering.bunchId
        shoot(bunchId, secondDay, "19:00", 8, gathering.members)
        shoot(bunchId, thirdDay, "19:00", 8, gathering.members)
        recalculate(bunchId, gatheringDay, secondDay, thirdDay)
        val ids = sameTime(bunchId)

        val ordered = orderedIds(bunchId)

        assertThat(ordered).containsExactly(ids.getValue(gatheringDay), ids.getValue(secondDay), ids.getValue(thirdDay))
        assertThat(orderedIds(bunchId)).isEqualTo(ordered)
    }

    @Test
    fun `the list has only the bundles of the bunch`() {
        val bunchId = bunchWithGathering().bunchId
        val otherBunch = bunchWithGathering().bunchId
        recalculate(bunchId, gatheringDay)
        recalculate(otherBunch, gatheringDay)

        assertThat(orderedIds(bunchId)).containsExactly(candidates(bunchId).single().id)
        assertThat(orderedIds(otherBunch)).hasSize(1)
        assertThat(orderedIds(Long.MAX_VALUE)).isEmpty()
    }

    private fun orderedIds(bunchId: Long): List<Long> = recommendation.orderedCandidates(bunchId).map { it.id }

    private fun idsByDay(bunchId: Long): Map<String, Long> =
        candidates(bunchId).associate { it.startDate.toString() to it.id }

    // Gives every bundle of the bunch the same time of the last photo added, so only the members and photos decide. The
    // rows are updated from the last number to the first, so the database does not hand them back in the order of their
    // numbers by chance and the order has to come from the query.
    private fun sameTime(bunchId: Long): Map<String, Long> {
        val at = daysAgo(2)
        candidates(bunchId).reversed().forEach { setLastPhotoAddedAt(it.id, at) }
        return idsByDay(bunchId)
    }
}
