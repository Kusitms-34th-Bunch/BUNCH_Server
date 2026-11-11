package com.bunch.bundle.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleCommentRepository
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.dto.BundleAttribution
import com.bunch.bundle.dto.BundleCommentResponse
import com.bunch.bundle.dto.BundleParticipantResponse
import com.bunch.bundle.domain.BundleStatus
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BundleAttributionService(
    private val participants: BundleParticipantRepository,
    private val comments: BundleCommentRepository,
    private val memberships: BunchMembershipRepository
) {
    @Transactional(readOnly = true)
    fun draft(bundle: Bundle): BundleAttribution {
        if (bundle.id <= 0 || bundle.status != BundleStatus.DRAFT) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        return assemble(listOf(bundle)).getValue(bundle.id)
    }

    @Transactional(readOnly = true)
    fun published(bundle: Bundle): BundleAttribution = publishedAll(listOf(bundle)).getValue(bundle.id)

    @Transactional(readOnly = true)
    fun draftParticipants(bundle: Bundle): List<BundleParticipantResponse> = draft(bundle).participants

    /** Internal assembler: caller must authorize all bundles before passing them here. Three bounded queries. */
    @Transactional(readOnly = true)
    fun publishedAll(bundles: Collection<Bundle>): Map<Long, BundleAttribution> {
        if (bundles.size > 500 || bundles.any { it.id <= 0 || it.status != BundleStatus.PUBLISHED }) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }
        return assemble(bundles)
    }

    private fun assemble(bundles: Collection<Bundle>): Map<Long, BundleAttribution> {
        if (bundles.isEmpty()) return emptyMap()
        val ids = bundles.map { it.id }.distinct()
        val peopleByBundle = participants.findAllByBundleIdInOrderByBundleIdAscJoinedAtAscIdAsc(ids)
            .groupBy { it.bundleId }
        val notesByBundle = comments.findAllByBundleIdInOrderByBundleIdAscIdAsc(ids).groupBy { it.bundleId }
        val profiles = memberships.findActiveProfilesForBunches(bundles.map { it.bunchId }.distinct())
            .associateBy { it.bunch.id to it.member.id }
        return bundles.associate { bundle ->
            val people = peopleByBundle[bundle.id].orEmpty().map { row ->
                val currentMembership = profiles[bundle.bunchId to row.memberId]
                val profile = currentMembership?.member
                val person = BundleParticipantResponse(
                    row.memberId, row.joinedAt, profile?.nickname ?: "작성자", profile?.imageUrl, profile != null
                )
                if (bundle.status == BundleStatus.DRAFT) {
                    person.copy(role = currentMembership?.role, creator = bundle.createdBy == row.memberId)
                } else person
            }
            val notes = notesByBundle[bundle.id].orEmpty().associateBy { it.memberId }
            val displayedNotes = people.mapNotNull { person ->
                notes[person.memberId]?.let { note ->
                    BundleCommentResponse(
                        note.memberId,
                        if (person.profileAvailable) note.content else "삭제된 코멘트입니다",
                        note.version, note.createdAt, note.updatedAt,
                        person.nickname, person.imageUrl, person.profileAvailable, note.id
                    )
                }
            }
            bundle.id to BundleAttribution(people, displayedNotes)
        }
    }
}
