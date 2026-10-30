package com.bunch.bunch.domain

import com.bunch.member.domain.AccountStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BunchMembershipRepository : JpaRepository<BunchMembership, Long> {
    fun findByMemberIdAndBunchIdAndStatus(
        memberId: Long,
        bunchId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE
    ): BunchMembership?

    fun countByMemberIdAndStatus(
        memberId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE
    ): Long

    fun countByBunchIdAndStatus(
        bunchId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE
    ): Long

    fun findFirstByMemberIdAndStatusOrderByLastUsedAtDescIdDesc(
        memberId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE
    ): BunchMembership?

    fun findAllByBunchIdAndStatus(
        bunchId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE
    ): List<BunchMembership>

    // One query loads each member and the profile image, so the list does not cost a query per member.
    // The main admin comes first; the nickname uses the database's code point order.
    @Query("""select m from BunchMembership m
        join fetch m.member member
        left join fetch member.profileImageAsset
        where m.bunch.id = :bunchId and m.status = :status and member.accountStatus = :accountStatus
        order by case when m.role = com.bunch.bunch.domain.MembershipRole.OWNER then 0 else 1 end,
        member.nickname asc""")
    fun findActiveMembers(
        bunchId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE,
        accountStatus: AccountStatus = AccountStatus.ACTIVE
    ): List<BunchMembership>

    @Query(value = """SELECT * FROM bunch_memberships
        WHERE member_id = :memberId AND jsonb_exists(join_requests, :key)""", nativeQuery = true)
    fun findJoinReplay(memberId: Long, key: String): BunchMembership?

    @Query("""select m.member.nickname from BunchMembership m
        where m.member.id = :memberId and m.bunch.id = :bunchId
        and m.status = :status and m.member.accountStatus = :accountStatus""")
    fun inviterNickname(
        memberId: Long,
        bunchId: Long,
        status: MembershipStatus = MembershipStatus.ACTIVE,
        accountStatus: AccountStatus = AccountStatus.ACTIVE
    ): String?
}
