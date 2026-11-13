package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bunch.domain.MembershipRole
import com.bunch.bunch.dto.ChangeMembershipRoleRequest
import com.bunch.bunch.dto.OwnerTransferRequest
import com.bunch.bunch.service.BunchMembershipManagementService
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleCommentResponse
import com.bunch.bundle.dto.BundleParticipantResponse
import com.bunch.bundle.dto.BundleRealtimeMessage
import com.bunch.bundle.dto.BundleTextRequest
import com.bunch.bundle.service.BundleRealtimeDestinations
import com.bunch.bundle.service.BundleService
import com.bunch.image.domain.ImageAsset
import com.bunch.image.domain.ImageAssetRepository
import com.bunch.photo.domain.Photo
import com.bunch.photo.domain.PhotoRepository
import com.bunch.photo.domain.ProcessingStatus
import com.bunch.photo.dto.PhotoDateChangeRequest
import com.bunch.photo.service.PhotoService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class BundleRefreshIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var management: BunchMembershipManagementService
    @Autowired
    private lateinit var bundleService: BundleService
    @Autowired
    private lateinit var photoService: PhotoService
    @Autowired
    private lateinit var bundles: BundleRepository
    @Autowired
    private lateinit var photos: PhotoRepository
    @Autowired
    private lateinit var assets: ImageAssetRepository
    @Autowired
    private lateinit var transactions: PlatformTransactionManager
    @Autowired
    private lateinit var bunchRows: com.bunch.bunch.domain.BunchRepository

    @MockitoBean
    private lateinit var messaging: SimpMessagingTemplate

    private val assetIds = mutableListOf<Long>()

    @BeforeEach
    fun resetMessages() {
        clearInvocations(messaging)
    }

    @AfterEach
    fun cleanupRefresh() {
        jdbc.update("DELETE FROM bundles")
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        assetIds.forEach {
            jdbc.update("UPDATE members SET profile_image_asset_id = NULL WHERE profile_image_asset_id = ?", it)
            jdbc.update("DELETE FROM image_assets WHERE id = ?", it)
        }
    }

    @Test
    fun `draft snapshot returns every current profile role and creator independently`() {
        val f = fixture()
        seedParticipants(f)
        val coOwner = member()
        join(coOwner, issue(f.owner, f.bunch))
        management.changeRole(f.owner, f.bunch, membership(coOwner, f.bunch),
            ChangeMembershipRoleRequest(f.ownerMembership, version(f), MembershipRole.CO_OWNER))
        bundleService.addParticipant(actor(f.owner), f.bunch, f.undated, coOwner, "add-co-owner")
        bundleService.get(f.owner, f.bunch, f.undated)
        setCurrentProfile(f.guest)

        val people = bundleService.get(f.guest, f.bunch, f.undated).participants

        assertThat(people.map { it.memberId }).containsExactlyInAnyOrder(f.owner, f.guest, coOwner)
        assertParticipant(people, f.owner, MembershipRole.OWNER, creator = true)
        assertParticipant(people, f.guest, MembershipRole.MEMBER, creator = false)
        assertParticipant(people, coOwner, MembershipRole.CO_OWNER, creator = false)
    }

    @Test
    fun `participants event returns current profiles roles and separate creator flag`() {
        val f = fixture()
        seedParticipants(f)
        changeRole(f)
        val added = member()
        join(added, issue(f.owner, f.bunch))
        bundleService.comment(actor(f.guest), f.bunch, f.undated,
            BundleTextRequest("현재 프로필로 보이는 코멘트", 0, BundleStatus.DRAFT), "profile-comment")
        setCurrentProfile(f.owner)
        setCurrentProfile(f.guest)
        setCurrentProfile(added)
        clearInvocations(messaging)

        bundleService.addParticipant(actor(f.owner), f.bunch, f.undated, added, "participant-profile")

        val sent = deliveries(setOf(BundleEventType.PARTICIPANTS))
        assertThat(sent).hasSize(1)
        assertThat(sent.single().first).isEqualTo(BundleRealtimeDestinations.topic(f.bunch, f.undated))
        val payload = sent.single().second.data as Map<*, *>
        val people = (payload["participants"] as List<*>).map { it as BundleParticipantResponse }
        assertThat(people.map { it.memberId }).containsExactlyInAnyOrder(f.owner, f.guest, added)
        assertParticipant(people, f.owner, MembershipRole.OWNER, creator = true)
        assertParticipant(people, f.guest, MembershipRole.CO_OWNER, creator = false)
        assertParticipant(people, added, MembershipRole.MEMBER, creator = false)
        val notes = (payload["comments"] as List<*>).map { it as BundleCommentResponse }
        assertThat(notes.map { it.memberId }).containsExactlyInAnyOrder(f.guest, added)
        val note = notes.single { it.memberId == f.guest }
        val profile = members.findById(f.guest).orElseThrow()
        assertThat(note.memberId).isEqualTo(f.guest)
        assertThat(note.content).isEqualTo("현재 프로필로 보이는 코멘트")
        assertThat(note.nickname).isEqualTo(profile.nickname)
        assertThat(note.imageUrl).isEqualTo(profile.imageUrl)
        assertThat(note.profileAvailable).isTrue()
    }

    @Test
    fun `published participants preserve profile attribution without exposing current roles`() {
        val f = fixture()
        seedParticipants(f, f.published)
        setCurrentProfile(f.owner)
        setCurrentProfile(f.guest)

        val people = bundleService.get(f.owner, f.bunch, f.published).participants

        assertThat(people.map { it.memberId }).containsExactlyInAnyOrder(f.owner, f.guest)
        people.forEach { person ->
            val profile = members.findById(person.memberId).orElseThrow()
            assertThat(person.role).isNull()
            assertThat(person.creator).isFalse()
            assertThat(person.nickname).isEqualTo(profile.nickname)
            assertThat(person.imageUrl).isEqualTo(profile.imageUrl)
            assertThat(person.profileAvailable).isTrue()
        }
    }

    @Test
    fun `real role change delivers permissions only after commit without touching draft metadata`() {
        val f = fixture()
        seedParticipants(f)
        setCurrentProfile(f.guest)
        val before = metadata(f)
        clearInvocations(messaging)
        TransactionTemplate(transactions).executeWithoutResult {
            changeRole(f)
            assertThat(deliveries()).isEmpty()
        }
        assertDeliveries(f, BundleEventType.PERMISSIONS)
        assertThat(metadata(f)).isEqualTo(before)
        val people = bundleService.get(f.guest, f.bunch, f.undated).participants
        assertParticipant(people, f.owner, MembershipRole.OWNER, creator = true)
        assertParticipant(people, f.guest, MembershipRole.CO_OWNER, creator = false)
        clearInvocations(messaging)
        changeRole(f) // already CO_OWNER: no new refresh
        assertThat(deliveries()).isEmpty()
    }

    @Test
    fun `real owner transfer delivers permissions after commit and retry is silent`() {
        val f = fixture()
        seedParticipants(f)
        setCurrentProfile(f.owner)
        setCurrentProfile(f.guest)
        val before = metadata(f)
        val key = UUID.randomUUID()
        val request = OwnerTransferRequest(f.ownerMembership, version(f), f.guestMembership)
        clearInvocations(messaging)
        TransactionTemplate(transactions).executeWithoutResult {
            management.transferOwner(f.owner, f.bunch, key, request)
            assertThat(deliveries()).isEmpty()
        }
        assertDeliveries(f, BundleEventType.PERMISSIONS)
        assertThat(metadata(f)).isEqualTo(before)
        val people = bundleService.get(f.guest, f.bunch, f.undated).participants
        assertParticipant(people, f.owner, MembershipRole.MEMBER, creator = true)
        assertParticipant(people, f.guest, MembershipRole.OWNER, creator = false)
        clearInvocations(messaging)
        management.transferOwner(f.owner, f.bunch, key, request)
        assertThat(deliveries()).isEmpty()
    }

    @Test
    fun `rolled back role and owner changes deliver nothing and preserve metadata`() {
        val f = fixture()
        val before = metadata(f)
        clearInvocations(messaging)
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            changeRole(f)
            assertThat(deliveries()).isEmpty()
            tx.setRollbackOnly()
        }
        assertThat(deliveries()).isEmpty()
        assertThat(metadata(f)).isEqualTo(before)
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            management.transferOwner(f.owner, f.bunch, UUID.randomUUID(),
                OwnerTransferRequest(f.ownerMembership, version(f), f.guestMembership))
            assertThat(deliveries()).isEmpty()
            tx.setRollbackOnly()
        }
        assertThat(deliveries()).isEmpty()
        assertThat(metadata(f)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT role FROM bunch_memberships WHERE id = ?",
            String::class.java, f.ownerMembership)).isEqualTo("OWNER")
    }

    @Test
    fun `actual photo changeDate refreshes only undated drafts after commit without content activity writes`() {
        val f = fixture()
        val photo = photo(f)
        val before = metadata(f)
        val day = LocalDate.of(2026, 10, 3)
        clearInvocations(messaging)
        TransactionTemplate(transactions).executeWithoutResult {
            photoService.changeDate(f.owner, f.bunch, PhotoDateChangeRequest(listOf(photo), day))
            assertThat(deliveries()).isEmpty()
        }
        val sent = deliveries()
        assertThat(sent).hasSize(1)
        assertThat(sent.single().first).isEqualTo(BundleRealtimeDestinations.topic(f.bunch, f.undated))
        val message = sent.single().second
        assertThat(message.type).isEqualTo(BundleEventType.DATE_CHIPS)
        assertThat(message.revision).isEqualTo(7)
        assertThat((message.data as Map<*, *>)["dateChips"]).isEqualTo(listOf(day))
        assertThat(metadata(f)).isEqualTo(before)
        clearInvocations(messaging)
        photoService.changeDate(f.owner, f.bunch, PhotoDateChangeRequest(listOf(photo), clearDate = true))
        assertThat(deliveries().map { it.second.type }).containsExactly(BundleEventType.DATE_CHIPS)
        assertThat((deliveries().single().second.data as Map<*, *>)["dateChips"]).isEqualTo(emptyList<LocalDate>())
        assertThat(metadata(f)).isEqualTo(before)
    }

    @Test
    fun `actual photo date rollback delivers nothing and keeps date chips and all clocks unchanged`() {
        val f = fixture()
        val photo = photo(f)
        val before = metadata(f)
        clearInvocations(messaging)
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            photoService.changeDate(f.owner, f.bunch,
                PhotoDateChangeRequest(listOf(photo), LocalDate.of(2026, 10, 3)))
            assertThat(deliveries()).isEmpty()
            tx.setRollbackOnly()
        }
        assertThat(deliveries()).isEmpty()
        assertThat(photos.findById(photo).orElseThrow().capturedDate).isNull()
        assertThat(metadata(f)).isEqualTo(before)
    }

    private fun fixture(): Fixture {
        val owner = member()
        val guest = member()
        val bunch = create(owner).id
        join(guest, issue(owner, bunch))
        val undated = draft(bunch, owner)
        val dated = bundles.saveAndFlush(Bundle(bunchId = bunch, createdBy = owner,
            date = LocalDate.of(2026, 9, 1), revision = 7)).id
        val published = bundles.saveAndFlush(Bundle(bunchId = bunch, createdBy = owner, revision = 7)).id
        jdbc.update("UPDATE bundles SET status = 'PUBLISHED', published_at = updated_at WHERE id = ?", published)
        val otherBunch = create(guest).id
        draft(otherBunch, guest)
        return Fixture(owner, guest, bunch, membership(owner, bunch), membership(guest, bunch), undated, dated, published)
    }

    private fun draft(bunch: Long, owner: Long) = bundles.saveAndFlush(
        Bundle(bunchId = bunch, createdBy = owner, revision = 7)
    ).id

    private fun membership(member: Long, bunch: Long): Long = jdbc.queryForObject(
        "SELECT id FROM bunch_memberships WHERE member_id = ? AND bunch_id = ?",
        Long::class.java, member, bunch
    )!!

    private fun version(f: Fixture): Long = jdbc.queryForObject(
        "SELECT membership_version FROM bunches WHERE id = ?", Long::class.java, f.bunch
    )!!

    private fun changeRole(f: Fixture) = management.changeRole(f.owner, f.bunch, f.guestMembership,
        ChangeMembershipRoleRequest(f.ownerMembership, version(f), MembershipRole.CO_OWNER))

    private fun photo(f: Fixture): Long {
        val asset = assets.saveAndFlush(ImageAsset(publicId = "refresh/${UUID.randomUUID()}", url = "https://test/photo"))
        assetIds.add(asset.id)
        val row = photos.saveAndFlush(Photo(bunch = bunchRows.findById(f.bunch).orElseThrow(),
            imageAsset = asset, fileHash = UUID.randomUUID().toString().replace("-", "").repeat(2),
            processingStatus = ProcessingStatus.READY))
        listOf(f.undated, f.dated, f.published).forEach { bundle ->
            jdbc.update("""INSERT INTO bundle_photos(bundle_id, photo_id, image_asset_id, position, added_by, added_at)
                VALUES (?, ?, ?, 0, ?, CURRENT_TIMESTAMP)""", bundle, row.id, asset.id, f.owner)
        }
        return row.id
    }

    private fun metadata(f: Fixture): List<Map<String, Any?>> =
        jdbc.queryForList("""SELECT id, revision, updated_at, title_version, date_version, location_version
            FROM bundles ORDER BY id""") + jdbc.queryForList(
            "SELECT id, last_activity_at FROM bunch_memberships WHERE bunch_id = ? ORDER BY id", f.bunch
        )

    private fun seedParticipants(f: Fixture, bundleId: Long = f.undated) {
        listOf(f.owner, f.guest).forEach { memberId ->
            jdbc.update("""INSERT INTO bundle_participants(bundle_id, member_id, joined_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)""", bundleId, memberId)
        }
    }

    private fun setCurrentProfile(memberId: Long) {
        val asset = assets.saveAndFlush(ImageAsset(
            publicId = "refresh-profile/${UUID.randomUUID()}", url = "https://test/profile/$memberId"
        ))
        assetIds.add(asset.id)
        val member = members.findById(memberId).orElseThrow()
        member.nickname = uniqueName()
        member.profileImageAsset = asset
        members.saveAndFlush(member)
    }

    private fun assertParticipant(
        people: List<BundleParticipantResponse>,
        memberId: Long,
        role: MembershipRole,
        creator: Boolean
    ) {
        val person = people.single { it.memberId == memberId }
        val profile = members.findById(memberId).orElseThrow()
        assertThat(person.role).isEqualTo(role)
        assertThat(person.creator).isEqualTo(creator)
        assertThat(person.nickname).isEqualTo(profile.nickname)
        assertThat(person.imageUrl).isEqualTo(profile.imageUrl)
        assertThat(person.profileAvailable).isTrue()
    }

    private fun actor(memberId: Long) = BundleActor(memberId, "refresh-$memberId")

    private fun deliveries(
        types: Set<BundleEventType> = setOf(BundleEventType.PERMISSIONS, BundleEventType.DATE_CHIPS)
    ): List<Pair<String, BundleRealtimeMessage<*>>> = mockingDetails(messaging).invocations
        .filter { it.method.name == "convertAndSend" && it.arguments.size == 2 }
        .mapNotNull { invocation ->
            val destination = invocation.arguments[0] as? String ?: return@mapNotNull null
            val message = invocation.arguments[1] as? BundleRealtimeMessage<*> ?: return@mapNotNull null
            if (message.type !in types) return@mapNotNull null
            destination to message
        }

    private fun assertDeliveries(f: Fixture, type: BundleEventType) {
        val sent = deliveries()
        assertThat(sent.map { it.first }).containsExactlyInAnyOrder(
            BundleRealtimeDestinations.topic(f.bunch, f.undated), BundleRealtimeDestinations.topic(f.bunch, f.dated)
        )
        assertThat(sent.map { it.second.type }).containsOnly(type)
        assertThat(sent.map { it.second.revision }).containsOnly(7L)
    }

    private data class Fixture(
        val owner: Long,
        val guest: Long,
        val bunch: Long,
        val ownerMembership: Long,
        val guestMembership: Long,
        val undated: Long,
        val dated: Long,
        val published: Long
    )
}
