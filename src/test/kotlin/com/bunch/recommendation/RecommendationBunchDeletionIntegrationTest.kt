package com.bunch.recommendation

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.dto.BunchDeletionRequest
import com.bunch.bunch.service.BunchDeletionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/** The real deletion of a bunch (#50) takes the recommended bundles of the bunch with it. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationBunchDeletionIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var deletions: BunchDeletionService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @Test
    fun `deleting a bunch deletes its candidates their photos and its noted dates`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        val candidate = candidates(bunchId).single()
        assertThat(photosOf(candidate.id)).hasSize(8)

        deleteBunch(ownerId, bunchId)

        assertThat(bunchRepository.existsById(bunchId)).isFalse()
        assertThat(count("bundle_candidates")).isZero()
        assertThat(count("candidate_photos")).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty_dates", Long::class.java)).isZero()
    }

    @Test
    fun `deleting a bunch leaves the candidates of another bunch`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val (otherOwner, otherBunch) = bunchWithOwner()
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, joined(ownerId, bunchId)))
        shoot(otherBunch, "2026-10-19", "19:00", 8, listOf(otherOwner, joined(otherOwner, otherBunch)))
        recalculate(bunchId, "2026-10-19")
        recalculate(otherBunch, "2026-10-19")

        deleteBunch(ownerId, bunchId)

        assertThat(candidates(bunchId)).isEmpty()
        val remaining = candidates(otherBunch).single()
        assertThat(photosOf(remaining.id)).hasSize(8)
    }

    private fun deleteBunch(ownerId: Long, bunchId: Long) {
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(ownerId, bunchId)!!
        val version = bunchRepository.findById(bunchId).orElseThrow().membershipVersion

        deletions.delete(ownerId, bunchId, UUID.randomUUID(), BunchDeletionRequest(membership.id, version, "삭제하기"))
    }
}
