package com.bunch.recommendation

import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleSnapshot
import com.bunch.bundle.dto.CreateBundleRequest
import com.bunch.bundle.service.BundleService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.util.UUID

/** A draft made from a recommended bundle (4.1.3 rules 5, 6 and 8, 4.3.11) and what its photos do to the rest. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationDraftIntegrationTest : RecommendationIntegrationSupport() {
    @Autowired
    private lateinit var bundles: BundleService

    @AfterEach
    fun cleanDraftGraph() {
        jdbc.update("DELETE FROM bundle_edit_leases")
        jdbc.update("DELETE FROM bundle_comments")
        jdbc.update("DELETE FROM bundle_participants")
        jdbc.update("DELETE FROM bundle_photos")
        jdbc.update("DELETE FROM bundle_requests")
        jdbc.update("DELETE FROM bundles")
    }

    @Test
    fun `a draft made from a recommended bundle starts with its title and keeps its first day`() {
        val gathering = bunchWithGathering(count = 8)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()

        val draft = draftOf(gathering, gathering.photoIds, candidate.id)

        assertThat(draft.title).isEqualTo(candidate.title)
        assertThat(recommendedDateOf(draft.id)).isEqualTo(candidate.startDate)
    }

    @Test
    fun `the recommended bundle is gone once the draft is made`() {
        val gathering = bunchWithGathering(count = 8)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()

        draftOf(gathering, gathering.photoIds, candidate.id)

        assertThat(candidates(gathering.bunchId)).isEmpty()
        assertThat(photosOf(candidate.id)).isEmpty()
    }

    @Test
    fun `the day of the recommended bundle is the first date chip even when other days have more photos`() {
        val gathering = bunchWithGathering(count = 8)
        val otherDay = shoot(gathering.bunchId, "2026-10-15", "12:00", 10, gathering.members)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()

        val draft = draftOf(gathering, gathering.photoIds + otherDay, candidate.id)

        assertThat(draft.dateChips).containsExactly(LocalDate.parse(gatheringDay), LocalDate.parse("2026-10-15"))
    }

    @Test
    fun `a draft made without a recommended bundle has no title no recommended day and the usual chips`() {
        val gathering = bunchWithGathering(count = 8)
        val otherDay = shoot(gathering.bunchId, "2026-10-15", "12:00", 10, gathering.members)
        recalculate(gathering.bunchId, gatheringDay)

        val draft = draftOf(gathering, gathering.photoIds + otherDay, recommendationId = null)

        assertThat(draft.title).isEmpty()
        assertThat(recommendedDateOf(draft.id)).isNull()
        assertThat(draft.dateChips).containsExactly(LocalDate.parse("2026-10-15"), LocalDate.parse(gatheringDay))
        assertThat(candidates(gathering.bunchId)).hasSize(1)
    }

    @Test
    fun `a recommended bundle that is not there any more is ignored`() {
        val gathering = bunchWithGathering(count = 8)

        val draft = draftOf(gathering, gathering.photoIds, recommendationId = 999_999_999L)

        assertThat(draft.title).isEmpty()
        assertThat(recommendedDateOf(draft.id)).isNull()
    }

    @Test
    fun `a recommended bundle of another bunch is ignored and stays`() {
        val gathering = bunchWithGathering(count = 8)
        val other = bunchWithGathering(count = 8)
        recalculate(other.bunchId, gatheringDay)
        val foreign = candidates(other.bunchId).single()

        val draft = draftOf(gathering, gathering.photoIds, foreign.id)

        assertThat(draft.title).isEmpty()
        assertThat(recommendedDateOf(draft.id)).isNull()
        assertThat(candidates(other.bunchId).map { it.id }).containsExactly(foreign.id)
    }

    @Test
    fun `the photos left after a recommended draft are not recommended again`() {
        val gathering = bunchWithGathering(count = 16)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()

        draftOf(gathering, gathering.photoIds.take(10), candidate.id)
        recalculate(gathering.bunchId, gatheringDay)

        assertThat(candidates(gathering.bunchId)).isEmpty()
    }

    @Test
    fun `a photo added after a recommended draft makes a new recommended bundle of the photos left and the new one`() {
        val gathering = bunchWithGathering(count = 16)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val left = gathering.photoIds.drop(10)
        draftOf(gathering, gathering.photoIds.take(10), candidate.id)

        val added = insertPhoto(gathering.bunchId, gatheringDay, "21:50:00", uploaders = listOf(gathering.members[1]))
        recalculate(gathering.bunchId, gatheringDay)

        val again = candidates(gathering.bunchId).single()
        assertThat(again.id).isNotEqualTo(candidate.id)
        assertThat(photosOf(again.id).map { it.first }).containsExactlyInAnyOrderElementsOf(left + added)
    }

    @Test
    fun `a draft made without a recommended bundle leaves the rest recommended and notes no used photos`() {
        val gathering = bunchWithGathering(count = 16)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val left = gathering.photoIds.drop(10)

        draftOf(gathering, gathering.photoIds.take(10), recommendationId = null)
        recalculate(gathering.bunchId, gatheringDay)

        val rest = candidates(gathering.bunchId).single()
        assertThat(rest.id).isEqualTo(candidate.id)
        assertThat(photosOf(rest.id).map { it.first }).containsExactlyInAnyOrderElementsOf(left)
        assertThat(usedPhotoIds(gathering.bunchId)).isEmpty()
    }

    @Test
    fun `deleting a recommended draft gives its photos back to be recommended`() {
        val gathering = bunchWithGathering(count = 8)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val draft = draftOf(gathering, gathering.photoIds, candidate.id)
        recalculate(gathering.bunchId, gatheringDay)
        assertThat(candidates(gathering.bunchId)).isEmpty()

        bundles.delete(BundleActor(gathering.members.first(), "session"), gathering.bunchId, draft.id, "delete")
        recalculate(gathering.bunchId, gatheringDay)

        assertThat(usedPhotoIds(gathering.bunchId)).isEmpty()
        val back = candidates(gathering.bunchId).single()
        assertThat(photosOf(back.id).map { it.first }).containsExactlyInAnyOrderElementsOf(gathering.photoIds)
    }

    @Test
    fun `the photos of a used recommended bundle are noted and a deleted photo is not noted any more`() {
        val gathering = bunchWithGathering(count = 16)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        draftOf(gathering, gathering.photoIds.take(10), candidate.id)
        assertThat(usedPhotoIds(gathering.bunchId)).containsExactlyInAnyOrderElementsOf(gathering.photoIds)
        val gone = gathering.photoIds.last()

        jdbc.update("DELETE FROM photo_uploaders WHERE photo_id = ?", gone)
        jdbc.update("DELETE FROM photos WHERE id = ?", gone)

        assertThat(usedPhotoIds(gathering.bunchId)).doesNotContain(gone).hasSize(15)
    }

    @Test
    fun `making a draft notes the dates of the bunch to be calculated again`() {
        val gathering = bunchWithGathering(count = 8)
        assertThat(notedDates(gathering.bunchId)).isEmpty()

        draftOf(gathering, gathering.photoIds, recommendationId = null)

        assertThat(notedDates(gathering.bunchId)).containsExactly(LocalDate.parse(gatheringDay))
    }

    @Test
    fun `deleting a draft gives its photos back to the recommendation`() {
        val gathering = bunchWithGathering(count = 8)
        val draft = draftOf(gathering, gathering.photoIds, recommendationId = null)
        recalculate(gathering.bunchId, gatheringDay)
        assertThat(candidates(gathering.bunchId)).isEmpty()
        jdbc.update("DELETE FROM recommendation_dirty_dates")

        bundles.delete(BundleActor(gathering.members.first(), "session"), gathering.bunchId, draft.id, "delete")

        assertThat(notedDates(gathering.bunchId)).containsExactly(LocalDate.parse(gatheringDay))
        recalculate(gathering.bunchId, gatheringDay)
        assertThat(candidates(gathering.bunchId)).hasSize(1)
    }

    @Test
    fun `failed selected participant insertion restores recommendation graph notifications and request key`() {
        val gathering = bunchWithGathering(count = 8, memberCount = 3)
        recalculate(gathering.bunchId, gatheringDay)
        val candidate = candidates(gathering.bunchId).single()
        val key = UUID.randomUUID()
        val actor = BundleActor(gathering.members.first(), "session")
        val request = CreateBundleRequest(
            gathering.photoIds, candidate.id, participantMemberIds = gathering.members.drop(1)
        )
        rejectWrite(
            "bundle_test_reject_participant", "bundle_participants", "INSERT",
            "WHEN (NEW.member_id = ${gathering.members.last()})"
        )
        try {
            assertThatThrownBy { bundles.create(actor, gathering.bunchId, key, request) }.isNotNull()
            assertThat(candidates(gathering.bunchId).map { it.id }).containsExactly(candidate.id)
            assertThat(photosOf(candidate.id)).hasSize(gathering.photoIds.size)
            assertThat(usedPhotoIds(gathering.bunchId)).isEmpty()
            for (table in listOf("bundles", "bundle_photos", "bundle_participants", "bundle_requests")) {
                assertThat(count(table)).isZero()
            }
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE bunch_id = ? AND type = 'DRAFT_PARTICIPANT_ADDED'",
                Long::class.java, gathering.bunchId
            )).isZero()
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS bundle_test_reject_participant ON bundle_participants")
            jdbc.execute("DROP FUNCTION IF EXISTS bunch_test_reject_write()")
        }
        val created = bundles.create(actor, gathering.bunchId, key, request)
        assertThat(created.participants.map { it.memberId }).containsExactlyElementsOf(gathering.members)
        assertThat(created.title).isEqualTo(candidate.title)
        assertThat(candidates(gathering.bunchId)).isEmpty()
        assertThat(bundles.create(actor, gathering.bunchId, key, request).id).isEqualTo(created.id)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM notifications WHERE bunch_id = ? AND type = 'DRAFT_PARTICIPANT_ADDED'",
            Long::class.java, gathering.bunchId
        )).isEqualTo(2)
    }

    private fun draftOf(gathering: Gathering, photoIds: List<Long>, recommendationId: Long?): BundleSnapshot =
        bundles.create(
            BundleActor(gathering.members.first(), "session"),
            gathering.bunchId,
            UUID.randomUUID(),
            CreateBundleRequest(photoIds, recommendationId, participantMemberIds = gathering.members.drop(1))
        )

    private fun usedPhotoIds(bunchId: Long): List<Long> = jdbc.queryForList(
        "SELECT photo_id FROM recommendation_used_photos WHERE bunch_id = ?",
        Long::class.java,
        bunchId
    )

    private fun recommendedDateOf(bundleId: Long): LocalDate? = jdbc.queryForList(
        "SELECT recommended_date FROM bundles WHERE id = ?",
        java.sql.Date::class.java,
        bundleId
    ).single()?.toLocalDate()

    private fun notedDates(bunchId: Long): List<LocalDate> = jdbc.queryForList(
        "SELECT captured_date FROM recommendation_dirty_dates WHERE bunch_id = ?",
        java.sql.Date::class.java,
        bunchId
    ).map { it.toLocalDate() }
}
