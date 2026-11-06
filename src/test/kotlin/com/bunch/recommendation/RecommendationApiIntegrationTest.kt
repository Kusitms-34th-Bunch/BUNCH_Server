package com.bunch.recommendation

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.dto.BunchDeletionRequest
import com.bunch.bunch.service.BunchDeletionService
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationApiIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var deletions: BunchDeletionService

    @Autowired
    private lateinit var bunchRepository: BunchRepository

    @Autowired
    private lateinit var memberships: BunchMembershipRepository

    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    private val imageUrl = "https://res.cloudinary.com/test/image/upload/test-http/photo.jpg"

    @Test
    fun `a bunch without recommended bundles gets an empty list`() {
        val (ownerId, bunchId) = bunchWithOwner()

        assertThat(list(ownerId, bunchId).path("bundles")).isEmpty()
    }

    @Test
    fun `the cards come in the order of the list`() {
        val gathering = bunchWithGathering()
        val bunchId = gathering.bunchId
        shoot(bunchId, "2026-10-21", "19:00", 8, gathering.members)
        recalculate(bunchId, gatheringDay, "2026-10-21")
        val ids = candidates(bunchId).associate { it.startDate.toString() to it.id }
        setLastPhotoAddedAt(ids.getValue(gatheringDay), daysAgo(3))
        setLastPhotoAddedAt(ids.getValue("2026-10-21"), daysAgo(1))

        val cards = list(gathering.members.first(), bunchId).path("bundles")

        val ordered = cards.map { it.path("id").asLong() }
        assertThat(ordered).containsExactly(ids.getValue("2026-10-21"), ids.getValue(gatheringDay))
    }

    @Test
    fun `a card has the values the sheet shows`() {
        val gathering = bunchWithGathering()
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()

        val card = list(gathering.members.first(), gathering.bunchId).path("bundles").single()

        assertThat(card.path("id").asLong()).isEqualTo(candidate.id)
        assertThat(card.path("title").asText()).isEqualTo(candidate.title)
        assertThat(card.path("startDate").asText()).isEqualTo(gatheringDay)
        assertThat(card.path("endDate").isNull).isTrue()
        assertThat(card.path("photoCount").asInt()).isEqualTo(8)
        assertThat(card.path("coverImageUrl").asText()).isEqualTo(imageUrl)
    }

    @Test
    fun `the members of a card start with the one who uploaded the most photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val third = joined(ownerId, bunchId)
        shoot(bunchId, gatheringDay, "19:00", 8, listOf(ownerId, ownerId, ownerId, other, other, third))
        recalculate(bunchId, gatheringDay)

        val card = list(third, bunchId).path("bundles").single()

        assertThat(card.path("uploaders").map { it.path("memberId").asLong() }).containsExactly(ownerId, other, third)
        assertThat(card.path("uploaders")[0].path("nickname").asText()).isNotBlank()
    }

    @Test
    fun `a bundle taken over midnight has a start and an end date`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, gatheringDay, "23:10", 4, listOf(ownerId, other))
        shoot(bunchId, "2026-10-20", "00:10", 4, listOf(ownerId, other))
        recalculate(bunchId, gatheringDay)

        val card = list(ownerId, bunchId).path("bundles").single()

        assertThat(card.path("startDate").asText()).isEqualTo(gatheringDay)
        assertThat(card.path("endDate").asText()).isEqualTo("2026-10-20")
    }

    @Test
    fun `every member of the bunch gets the same list and other bunches are not in it`() {
        val gathering = bunchWithGathering()
        bunchWithGathering().also { recalculate(it.bunchId, gatheringDay) }
        recalculate(gathering.bunchId, gatheringDay)

        val first = list(gathering.members.first(), gathering.bunchId)
        val second = list(gathering.members.last(), gathering.bunchId)

        assertThat(first.path("bundles")).hasSize(1)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `the detail has only the photos of the card in the order they are applied`() {
        val gathering = bunchWithGathering(count = 12)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val expected = photosOf(candidate.id).filter { it.second != null }.sortedBy { it.second }.map { it.first }

        val detail = detail(gathering.members.first(), gathering.bunchId, candidate.id)

        assertThat(detail.path("id").asLong()).isEqualTo(candidate.id)
        assertThat(detail.path("title").asText()).isEqualTo(candidate.title)
        assertThat(detail.path("photos").map { it.path("photoId").asLong() }).isEqualTo(expected)
        assertThat(detail.path("photos")).hasSize(10)
    }

    @Test
    fun `a photo of the detail has its images date and members`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = (0 until 6).map {
            val uploaders = if (it == 2) listOf(ownerId, other) else listOf(if (it % 2 == 0) ownerId else other)
            insertPhoto(bunchId, gatheringDay, "19:%02d:00".format(it * 5), uploaders = uploaders)
        }
        recalculate(bunchId, gatheringDay)

        val detail = detail(ownerId, bunchId, candidates(bunchId).single().id).path("photos")

        assertThat(detail.map { it.path("photoId").asLong() }).isEqualTo(photos)
        val shared = detail[2]
        assertThat(shared.path("uploaders").map { it.path("memberId").asLong() }).containsExactly(ownerId, other)
        assertThat(shared.path("uploaders")[0].path("nickname").asText()).isNotBlank()
        assertThat(shared.path("imageUrl").asText()).isEqualTo(imageUrl)
        assertThat(shared.path("thumbnailUrl").asText()).contains("c_fill").endsWith("photo.jpg")
        assertThat(shared.path("capturedDate").asText()).isEqualTo(gatheringDay)
        assertThat(shared.path("capturedTime").asText()).isEqualTo("19:10:00")
        assertThat(detail[1].path("uploaders")).hasSize(1)
    }

    @Test
    fun `a deleted photo is gone from the list and the detail at once`() {
        val gathering = bunchWithGathering()
        val owner = gathering.members.first()
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val firstPhoto = photosOf(candidate.id).single { it.second == 1 }.first

        deletePhotos(owner, gathering.bunchId, firstPhoto)

        assertThat(list(owner, gathering.bunchId).path("bundles").single().path("photoCount").asInt()).isEqualTo(7)
        val photos = detail(owner, gathering.bunchId, candidate.id).path("photos")
        assertThat(photos).hasSize(7)
        assertThat(photos.map { it.path("photoId").asLong() }).doesNotContain(firstPhoto)
    }

    @Test
    fun `a photo whose date was cleared is left out`() {
        val gathering = bunchWithGathering()
        val owner = gathering.members.first()
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        // Only a date the user set, on a photo without a shooting time, can be cleared (3.4.1): the photo is made one.
        jdbc.update(
            "UPDATE photos SET date_source = 'USER', captured_time = NULL WHERE id = ?",
            gathering.photoIds.first()
        )

        mvc.perform(
            post("/api/v1/bunches/${gathering.bunchId}/photos/date")
                .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[${gathering.photoIds.first()}],"clearDate":true}""")
        ).andExpect(status().isOk)

        val photos = detail(owner, gathering.bunchId, candidate.id).path("photos")
        assertThat(photos.map { it.path("photoId").asLong() }).doesNotContain(gathering.photoIds.first())
        assertThat(photos).hasSize(7)
    }

    @Test
    fun `a bundle whose card photos were all deleted is left out of the list and not found`() {
        val gathering = bunchWithGathering()
        val owner = gathering.members.first()
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()

        deletePhotos(owner, gathering.bunchId, *gathering.photoIds.toLongArray())

        assertThat(list(owner, gathering.bunchId).path("bundles")).isEmpty()
        request(owner, gathering.bunchId, candidate.id).andExpect(status().isNotFound)
    }

    @Test
    fun `a bundle that does not exist or belongs to another bunch is not found`() {
        val gathering = bunchWithGathering()
        val other = bunchWithGathering()
        recalculate(gathering.bunchId, gatheringDay)
        recalculate(other.bunchId, gatheringDay)
        val owner = gathering.members.first()
        val ofOtherBunch = candidates(other.bunchId).single().id
        val deleted = candidates(gathering.bunchId).single().id

        request(owner, gathering.bunchId, Long.MAX_VALUE).expectError(ErrorCode.RESOURCE_NOT_FOUND)
        request(owner, gathering.bunchId, ofOtherBunch).expectError(ErrorCode.RESOURCE_NOT_FOUND)

        jdbc.update("DELETE FROM bundle_candidates WHERE id = ?", deleted)

        request(owner, gathering.bunchId, deleted).expectError(ErrorCode.RESOURCE_NOT_FOUND)
    }

    @Test
    fun `someone who is not a member of the bunch is refused`() {
        val gathering = bunchWithGathering()
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val outsider = member()

        request(outsider, gathering.bunchId).expectError(ErrorCode.FORBIDDEN)
        request(outsider, gathering.bunchId, candidate.id).expectError(ErrorCode.FORBIDDEN)
        request(outsider, Long.MAX_VALUE).expectError(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `a member who left the bunch is refused`() {
        val gathering = bunchWithGathering()
        val leaver = gathering.members.last()
        jdbc.update(
            "UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            gathering.bunchId,
            leaver
        )

        request(leaver, gathering.bunchId).expectError(ErrorCode.FORBIDDEN)
    }

    @Test
    fun `a member whose sign up is not complete is refused`() {
        val gathering = bunchWithGathering()

        request(member(completed = false), gathering.bunchId).expectError(ErrorCode.SIGNUP_INCOMPLETE)
    }

    @Test
    fun `a call without a sign in is refused`() {
        val gathering = bunchWithGathering()

        mvc.perform(get("/api/v1/bunches/${gathering.bunchId}/recommended-bundles"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a member of a deleted bunch gets the access notice`() {
        val gathering = bunchWithGathering()
        val (owner, recipient) = gathering.members
        val membership = memberships.findByMemberIdAndBunchIdAndStatus(owner, gathering.bunchId)!!
        val version = bunchRepository.findById(gathering.bunchId).orElseThrow().membershipVersion
        deletions.delete(
            owner,
            gathering.bunchId,
            UUID.randomUUID(),
            BunchDeletionRequest(membership.id, version, "삭제하기")
        )

        request(recipient, gathering.bunchId).expectError(ErrorCode.BUNCH_ACCESS_UNAVAILABLE)
    }

    private fun list(memberId: Long, bunchId: Long): JsonNode =
        request(memberId, bunchId).andExpect(status().isOk).andReturn().data()

    private fun detail(memberId: Long, bunchId: Long, id: Long): JsonNode =
        request(memberId, bunchId, id).andExpect(status().isOk).andReturn().data()

    private fun request(memberId: Long, bunchId: Long, id: Long? = null): ResultActions {
        val path = "/api/v1/bunches/$bunchId/recommended-bundles" + (id?.let { "/$it" } ?: "")

        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(memberId)))
    }

    private fun deletePhotos(memberId: Long, bunchId: Long, vararg photoIds: Long) {
        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/delete")
                .header(HttpHeaders.AUTHORIZATION, bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[${photoIds.joinToString(",")}]}""")
        ).andExpect(status().isOk)
    }
}
