package com.bunch.bundle

import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.domain.BundlePhoto
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleLocation
import com.bunch.bundle.domain.BundleLocationRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.bundle.dto.BundlePresenceData
import com.bunch.bundle.dto.BundlePresenceCommand
import com.bunch.bundle.service.BundleAccessService
import com.bunch.bundle.service.BundlePresenceService
import com.bunch.bundle.service.BundleRealtimeSessionRegistry
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Optional

class BundlePresenceServiceTest {
    private val access = mock(BundleAccessService::class.java)
    private val registry = BundleRealtimeSessionRegistry()
    private val messaging = mock(SimpMessagingTemplate::class.java)
    private val clock = TestClock()
    private val photos = mock(BundlePhotoRepository::class.java)
    private val locations = mock(BundleLocationRepository::class.java)
    private val presence = BundlePresenceService(access, registry, messaging, clock, photos, locations)
    private val bundle = Bundle(id = 2, bunchId = 1, createdBy = 10)
    private val owner = BundleActor(10, "auth-owner")
    private val other = BundleActor(20, "auth-other")

    init {
        listOf(owner, other).forEach { actor ->
            `when`(access.read(actor.memberId, 1, 2)).thenReturn(bundle)
            `when`(access.lock(actor.memberId, 1, 2)).thenReturn(bundle)
            `when`(access.isParticipant(actor.memberId, 2)).thenReturn(true)
        }
        registry.bind("a", owner)
        registry.bind("b", owner)
        registry.bind("c", other)
    }

    @Test
    fun `location editing identifies each location and allows several editors on the same item`() {
        val first = mock(BundleLocation::class.java)
        val second = mock(BundleLocation::class.java)
        `when`(first.bundleId).thenReturn(2)
        `when`(second.bundleId).thenReturn(2)
        `when`(locations.findById(11)).thenReturn(Optional.of(first))
        `when`(locations.findById(22)).thenReturn(Optional.of(second))
        send("a", owner, "BEGIN", "LOCATION:11")
        val different = send("c", other, "BEGIN", "LOCATION:22")
        assertThat(different.editing.map { it.memberId to it.target })
            .containsExactly(10L to "LOCATION:11", 20L to "LOCATION:22")
        send("c", other, "END", "LOCATION:22")
        val same = send("c", other, "BEGIN", "LOCATION:11")
        assertThat(same.editing.map { it.memberId to it.target })
            .containsExactly(10L to "LOCATION:11", 20L to "LOCATION:11")
        assertThat(send("a", owner, "END", "LOCATION:11").editing.single().memberId).isEqualTo(20)
        assertThat(bundle.revision).isZero()
        assertThat(bundle.updatedAt).isEqualTo(bundle.createdAt)
    }

    @Test
    fun `location editing validates ownership and clears only deleted location targets`() {
        val first = mock(BundleLocation::class.java)
        val second = mock(BundleLocation::class.java)
        val foreign = mock(BundleLocation::class.java)
        `when`(first.bundleId).thenReturn(2)
        `when`(second.bundleId).thenReturn(2)
        `when`(second.id).thenReturn(22)
        `when`(foreign.bundleId).thenReturn(999)
        `when`(locations.findById(11)).thenReturn(Optional.of(first))
        `when`(locations.findById(22)).thenReturn(Optional.of(second))
        `when`(locations.findById(33)).thenReturn(Optional.of(foreign))
        send("a", owner, "BEGIN", "LOCATION:11")
        send("c", other, "BEGIN", "LOCATION:22")
        listOf("LOCATION:33", "LOCATION:44", "LOCATION:0", "LOCATION:-1", "LOCATION:01").forEach { target ->
            assertThatThrownBy { send("a", owner, "BEGIN", target) }.isInstanceOf(BusinessException::class.java)
        }
        `when`(locations.findAllByBundleIdOrderByPositionAscIdAsc(2)).thenReturn(listOf(second))
        presence.changed(event(BundleEventType.LOCATION))
        assertThat(presence.get(10, 1, 2).editing.single().target).isEqualTo("LOCATION:22")
        `when`(locations.findById(11)).thenReturn(Optional.empty())
        send("a", owner, "END", "LOCATION:11")
        assertThat(presence.get(10, 1, 2).editing.single().target).isEqualTo("LOCATION:22")
    }

    @Test
    fun `begin editing is nonexclusive target scoped and never changes content clocks`() {
        send("a", owner, "BEGIN", "DATE")
        send("b", owner, "BEGIN", "LOCATION")
        send("c", other, "BEGIN", "DATE")
        val data = presence.get(10, 1, 2)
        assertThat(data.editing.map { it.memberId to it.target })
            .containsExactly(10L to "DATE", 10L to "LOCATION", 20L to "DATE")
        assertThat(data.typing).isEmpty()
        assertThat(send("a", owner, "END", "DATE").editing.map { it.target })
            .containsExactly("LOCATION", "DATE")
        assertThat(send("b", owner, "BLUR").editing.map { it.memberId }).containsExactly(20L)
        assertThat(bundle.revision).isZero()
        assertThat(bundle.updatedAt).isEqualTo(bundle.createdAt)
    }

    @Test
    fun `editing expires fifteen seconds after begin while view does not prolong it`() {
        send("a", owner, "BEGIN", "DATE")
        clock.advance(10)
        send("a", owner, "VIEW")
        clock.advance(5)
        assertThat(presence.get(10, 1, 2).editing).isEmpty()
        send("a", owner, "BEGIN", "LOCATION")
        clock.advance(10)
        send("a", owner, "BEGIN", "LOCATION")
        clock.advance(10)
        assertThat(presence.get(10, 1, 2).editing.single().expiresAt).isEqualTo(clock.instant().plusSeconds(5))
        registry.remove("a")
        presence.disconnected("a")
        clock.advance(5)
        assertThat(presence.get(10, 1, 2).editing).isEmpty()
    }

    @Test
    fun `disconnect never extends field editing beyond its last begin deadline`() {
        send("a", owner, "BEGIN", "DATE")
        clock.advance(10)
        send("a", owner, "VIEW")
        registry.remove("a")
        presence.disconnected("a")
        assertThat(presence.get(10, 1, 2).editing.single().expiresAt).isEqualTo(Instant.EPOCH.plusSeconds(15))
        clock.advance(5)
        assertThat(presence.get(10, 1, 2).editing).isEmpty()
        assertThat(presence.get(10, 1, 2).members).hasSize(1)
    }

    @Test
    fun `editing cleanup on background publication deletion and permission loss includes all connections`() {
        send("a", owner, "BEGIN", "LOCATION")
        send("b", owner, "BEGIN", "DATE")
        send("c", other, "BEGIN", "PHOTOS")
        assertThat(send("a", owner, "BACKGROUND").editing).hasSize(2)
        doThrow(BusinessException(ErrorCode.FORBIDDEN)).`when`(access).requireParticipant(owner.memberId, bundle)
        presence.changed(event(BundleEventType.PERMISSIONS))
        assertThat(presence.get(20, 1, 2).editing.single().memberId).isEqualTo(20)
        presence.changed(event(BundleEventType.PUBLISHED))
        assertThat(presence.get(20, 1, 2).editing).isEmpty()
        send("c", other, "BEGIN", "LOCATION")
        presence.changed(event(BundleEventType.DELETED))
        assertThat(presence.get(20, 1, 2).editing).isEmpty()
    }

    @Test
    fun `begin validates participant and target while title preview keeps three second expiry`() {
        listOf("DATE", "LOCATION", "PHOTOS", "VISIBILITY").forEach {
            assertThat(send("a", owner, "BEGIN", it).editing.map { state -> state.target }).contains(it)
        }
        listOf("UNKNOWN", "PHOTO:0", "PHOTO:01", "PHOTO:-1", "PHOTO:1:2", "PHOTO:x").forEach {
            assertThatThrownBy { send("a", owner, "BEGIN", it) }.isInstanceOf(BusinessException::class.java)
        }
        val foreignPhoto = mock(BundlePhoto::class.java)
        `when`(foreignPhoto.bundleId).thenReturn(999)
        `when`(photos.findById(77)).thenReturn(Optional.of(foreignPhoto))
        assertThatThrownBy { send("a", owner, "BEGIN", "PHOTO:77") }.isInstanceOf(BusinessException::class.java)
        doThrow(BusinessException(ErrorCode.FORBIDDEN)).`when`(access).requireParticipant(other.memberId, bundle)
        assertThatThrownBy { send("c", other, "BEGIN", "DATE") }.isInstanceOf(BusinessException::class.java)
        send("a", owner, "TYPE", "TITLE", "preview")
        clock.advance(3)
        assertThat(presence.get(10, 1, 2).typing).isEmpty()
    }

    @Test
    fun `photo editing validates the live row and clears when its photo is removed`() {
        val photo = mock(BundlePhoto::class.java)
        `when`(photo.bundleId).thenReturn(2)
        `when`(photos.findById(88)).thenReturn(Optional.of(photo))
        assertThat(send("a", owner, "BEGIN", "PHOTO:88").editing.single().target).isEqualTo("PHOTO:88")
        assertThatThrownBy { send("c", other, "BEGIN", "PICK:88") }
            .isInstanceOf(BusinessException::class.java)
        assertThat(presence.get(10, 1, 2).editing.single().target).isEqualTo("PHOTO:88")
        `when`(photos.findAllByBundleIdOrderByPositionAscIdAsc(2)).thenReturn(emptyList())
        presence.changed(event(BundleEventType.PHOTOS))
        assertThat(presence.get(10, 1, 2).editing).isEmpty()
        // A delayed END must be able to clear a deleted target without loading it again.
        send("a", owner, "END", "PHOTO:88")
    }

    @Test
    fun `view heartbeat preserves entry but never extends connected typing past three seconds`() {
        send("a", owner, "TYPE", "TITLE", "hello")
        clock.advance(2)
        send("a", owner, "VIEW")
        clock.advance(1)
        presence.expire()
        val data = presence.get(10, 1, 2)
        assertThat(data.typing).isEmpty()
        assertThat(data.members.single().expiresAt).isEqualTo(clock.instant().plusSeconds(14))
        clock.advance(14)
        assertThat(presence.get(10, 1, 2).members).isEmpty()
        assertThat(bundle.revision).isZero()
        assertThat(bundle.updatedAt).isEqualTo(bundle.createdAt)
    }

    @Test
    fun `disconnect grace uses last signal plus fifteen seconds and never revives idle input`() {
        send("a", owner, "TYPE", "TITLE", "hello")
        clock.advance(1)
        registry.remove("a")
        presence.disconnected("a")
        clock.advance(2)
        assertThat(presence.get(10, 1, 2).typing.single().expiresAt)
            .isEqualTo(Instant.EPOCH.plusSeconds(15))
        clock.advance(11)
        presence.disconnected("a")
        assertThat(presence.get(10, 1, 2).typing).hasSize(1)
        clock.advance(1)
        assertThat(presence.get(10, 1, 2).members).isEmpty()

        send("b", owner, "TYPE", "TITLE", "already idle")
        clock.advance(3)
        registry.remove("b")
        presence.disconnected("b")
        assertThat(presence.get(10, 1, 2).typing).isEmpty()
    }

    @Test
    fun `lost registry identity also starts bounded disconnect cleanup`() {
        send("a", owner, "TYPE", "COMMENT", "hello")
        registry.remove("a")
        presence.expire()
        clock.advance(3)
        assertThat(presence.get(10, 1, 2).typing).hasSize(1)
        clock.advance(12)
        presence.expire()
        assertThat(presence.get(10, 1, 2).members).isEmpty()
    }

    @Test
    fun `multiple sockets aggregate latest actual input without blocking either member`() {
        send("a", owner, "TYPE", "TITLE", "first")
        send("b", owner, "TYPE", "TITLE", "second")
        send("c", other, "TYPE", "COMMENT", "other member")
        var data = presence.get(10, 1, 2)
        assertThat(data.members.map { it.memberId }).containsExactly(10, 20)
        assertThat(data.typing.map { it.text }).containsExactly("second", "other member")
        send("b", owner, "BLUR")
        data = presence.get(10, 1, 2)
        assertThat(data.typing.map { it.text }).containsExactly("first", "other member")
        send("b", owner, "LEAVE")
        assertThat(presence.get(10, 1, 2).members).hasSize(2)
        send("a", owner, "BACKGROUND")
        assertThat(presence.get(20, 1, 2).members.map { it.memberId }).containsExactly(20)
        send("c", other, "STOP")
        assertThat(presence.get(20, 1, 2).typing).isEmpty()
    }

    @Test
    fun `input identity is authenticated and draft participant is required`() {
        assertThatThrownBy { send("a", other, "TYPE", "COMMENT", "spoof") }
            .isInstanceOf(BusinessException::class.java)
        doThrow(BusinessException(ErrorCode.FORBIDDEN)).`when`(access).requireParticipant(other.memberId, bundle)
        assertThatThrownBy { send("c", other, "TYPE", "COMMENT", "forbidden") }
            .isInstanceOf(BusinessException::class.java)
        assertThat(send("c", other, "VIEW").typing).isEmpty()
        verify(access, atLeastOnce()).lock(other.memberId, 1, 2)
        verify(access, never()).lock(999, 1, 2)
        bundle.status = BundleStatus.PUBLISHED
        assertThatThrownBy { send("a", owner, "VIEW") }.isInstanceOf(BusinessException::class.java)
    }

    @Test
    fun `comment validates graphemes title permits overlength and preview preserves raw text`() {
        val emoji = "👨‍👩‍👧‍👦".repeat(400)
        assertThat(send("a", owner, "TYPE", "COMMENT", emoji).typing.single().text).isEqualTo(emoji)
        assertThatThrownBy { send("a", owner, "TYPE", "COMMENT", emoji + "a") }
            .isInstanceOf(BusinessException::class.java)
        val title = "  " + "a".repeat(41) + "  "
        assertThat(send("a", owner, "TYPE", "TITLE", title).typing.single().text).isEqualTo(title)
        listOf("a\nb", "a\u0000b", "a".repeat(256 * 1024 + 1)).forEach { invalid ->
            assertThatThrownBy { send("a", owner, "TYPE", "TITLE", invalid) }
                .isInstanceOf(BusinessException::class.java)
        }
        assertThat(send("a", owner, "TYPE", "TITLE", "").typing.single().text).isEmpty()
    }

    @Test
    fun `publish delete and lost participation clear all affected connections`() {
        send("a", owner, "TYPE", "TITLE", "a")
        send("b", owner, "TYPE", "COMMENT", "b")
        send("c", other, "VIEW")
        doThrow(BusinessException(ErrorCode.FORBIDDEN)).`when`(access).requireParticipant(owner.memberId, bundle)
        presence.changed(event(BundleEventType.PARTICIPANTS))
        assertThat(presence.get(20, 1, 2).members.map { it.memberId }).containsExactly(20)
        presence.changed(event(BundleEventType.PUBLISHED))
        assertThat(presence.get(20, 1, 2).members).isEmpty()
        send("c", other, "VIEW")
        presence.changed(event(BundleEventType.DELETED))
        assertThat(presence.get(20, 1, 2).members).isEmpty()
    }

    @Test
    fun `lost membership clears presence on committed permission signal`() {
        send("a", owner, "VIEW")
        send("c", other, "VIEW")
        doThrow(BusinessException(ErrorCode.FORBIDDEN)).`when`(access).read(owner.memberId, 1, 2)
        presence.changed(event(BundleEventType.PERMISSIONS))
        assertThat(presence.get(20, 1, 2).members.map { it.memberId }).containsExactly(20)
    }

    @Test
    fun `command waiting for database lock does not block presence cleanup`() {
        send("c", other, "VIEW")
        val lockEntered = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        `when`(access.lock(owner.memberId, 1, 2)).thenAnswer {
            lockEntered.countDown()
            check(releaseLock.await(5, TimeUnit.SECONDS))
            bundle
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pendingCommand = executor.submit<BundlePresenceData> {
                send("a", owner, "VIEW")
            }
            assertThat(lockEntered.await(2, TimeUnit.SECONDS)).isTrue()
            val cleanup = executor.submit {
                presence.changed(event(BundleEventType.DELETED))
            }
            cleanup.get(2, TimeUnit.SECONDS)
            assertThat(presence.get(20, 1, 2).members).isEmpty()
            releaseLock.countDown()
            pendingCommand.get(2, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `viewer later joining and typing is removed when that participation ends`() {
        `when`(access.isParticipant(owner.memberId, 2)).thenReturn(false)
        send("a", owner, "VIEW")
        send("b", owner, "VIEW")
        `when`(access.isParticipant(owner.memberId, 2)).thenReturn(true)
        send("a", owner, "TYPE", "COMMENT", "joined after opening")
        send("b", owner, "TYPE", "TITLE", "second socket joined")
        doThrow(BusinessException(ErrorCode.FORBIDDEN)).`when`(access).requireParticipant(owner.memberId, bundle)
        presence.changed(event(BundleEventType.PARTICIPANTS))
        val data = presence.get(20, 1, 2)
        assertThat(data.members).isEmpty()
        assertThat(data.typing).isEmpty()
    }

    @Test
    fun `one connection keeps photo upload date and location independently and ends only one target`() {
        send("a", owner, "BEGIN", "PHOTOS")
        send("a", owner, "BEGIN", "DATE")
        send("a", owner, "BEGIN", "LOCATION")
        send("c", other, "BEGIN", "PHOTOS")
        assertThat(presence.get(10, 1, 2).editing.map { it.memberId to it.target }).containsExactly(
            10L to "DATE", 10L to "LOCATION", 10L to "PHOTOS", 20L to "PHOTOS"
        )
        assertThat(send("a", owner, "END", "DATE").editing.map { it.memberId to it.target }).containsExactly(
            10L to "LOCATION", 10L to "PHOTOS", 20L to "PHOTOS"
        )
        assertThat(send("a", owner, "BLUR", "LOCATION").editing.map { it.memberId to it.target }).containsExactly(
            10L to "PHOTOS", 20L to "PHOTOS"
        )
        send("a", owner, "TYPE", "COMMENT", "upload continues")
        val stopped = send("a", owner, "STOP", "COMMENT")
        assertThat(stopped.typing).isEmpty()
        assertThat(stopped.editing.map { it.memberId to it.target }).containsExactly(
            10L to "PHOTOS", 20L to "PHOTOS"
        )
        assertThat(send("a", owner, "END", "PHOTOS").editing.single().memberId).isEqualTo(20)
        assertThat(bundle.revision).isZero()
        assertThat(bundle.titleVersion).isZero()
        assertThat(bundle.dateVersion).isZero()
        assertThat(bundle.locationVersion).isZero()
        assertThat(bundle.updatedAt).isEqualTo(bundle.createdAt)
    }

    @Test
    fun `focus alone creates no title or comment indicator and pick begin is rejected`() {
        send("a", owner, "BEGIN", "PHOTOS")
        listOf("TITLE", "COMMENT").forEach { target ->
            val focused = send("a", owner, "BEGIN", target)
            assertThat(focused.editing.map { it.target }).containsExactly("PHOTOS")
            assertThat(focused.typing).isEmpty()
        }
        val photo = mock(BundlePhoto::class.java)
        `when`(photo.bundleId).thenReturn(2)
        `when`(photos.findById(88)).thenReturn(Optional.of(photo))
        assertThatThrownBy { send("a", owner, "BEGIN", "PICK:88") }
            .isInstanceOf(BusinessException::class.java)
        assertThat(presence.get(10, 1, 2).editing.map { it.target }).containsExactly("PHOTOS")
        send("a", owner, "TYPE", "TITLE", "actual input")
        // An independent field BEGIN or legacy focus signal must not erase actual typing.
        send("a", owner, "BEGIN", "DATE")
        send("a", owner, "BEGIN", "COMMENT")
        assertThat(presence.get(10, 1, 2).typing.single().text).isEqualTo("actual input")
        send("a", owner, "BLUR", "COMMENT")
        assertThat(presence.get(10, 1, 2).typing.single().target).isEqualTo("TITLE")
        clock.advance(3)
        val expired = presence.get(10, 1, 2)
        assertThat(expired.typing).isEmpty()
        assertThat(expired.editing.map { it.target }).containsExactly("DATE", "PHOTOS")
    }

    @Test
    fun `targeted blur and stop validate syntax before changing independent work`() {
        send("a", owner, "BEGIN", "PHOTOS")
        send("a", owner, "BEGIN", "DATE")
        send("a", owner, "TYPE", "COMMENT", "still typing")
        listOf("BLUR", "STOP").forEach { action ->
            listOf("UNKNOWN", "LOCATION:0", "PHOTO:-1", "PICK:01").forEach { invalid ->
                assertThatThrownBy { send("a", owner, action, invalid) }
                    .isInstanceOf(BusinessException::class.java)
            }
            assertThatThrownBy { send("a", owner, action, "DATE", "unexpected text") }
                .isInstanceOf(BusinessException::class.java)
            // Cleanup is valid even if the item has already been deleted.
            send("a", owner, action, "LOCATION:11")
            val data = presence.get(10, 1, 2)
            assertThat(data.editing.map { it.target }).containsExactly("DATE", "PHOTOS")
            assertThat(data.typing.single().text).isEqualTo("still typing")
        }
        verify(locations, never()).findById(11)
    }

    @Test
    fun `renewal and disconnect cleanup respect each editing targets own deadline`() {
        send("a", owner, "BEGIN", "PHOTOS")
        send("a", owner, "BEGIN", "DATE")
        clock.advance(10)
        send("a", owner, "BEGIN", "PHOTOS")
        val active = presence.get(10, 1, 2)
        assertThat(active.editing.associate { it.target to it.expiresAt }).containsExactlyInAnyOrderEntriesOf(mapOf(
            "DATE" to Instant.EPOCH.plusSeconds(15), "PHOTOS" to Instant.EPOCH.plusSeconds(25)
        ))
        registry.remove("a")
        presence.disconnected("a")
        clock.advance(5)
        assertThat(presence.get(10, 1, 2).editing.single().target).isEqualTo("PHOTOS")
        clock.advance(10)
        assertThat(presence.get(10, 1, 2).editing).isEmpty()
        assertThat(presence.get(10, 1, 2).members).isEmpty()
    }

    @Test
    fun `deleted item cleanup preserves simultaneous upload and date editing on same connection`() {
        val location = mock(BundleLocation::class.java)
        val photo = mock(BundlePhoto::class.java)
        `when`(location.bundleId).thenReturn(2)
        `when`(photo.bundleId).thenReturn(2)
        `when`(locations.findById(11)).thenReturn(Optional.of(location))
        `when`(photos.findById(88)).thenReturn(Optional.of(photo))
        send("a", owner, "BEGIN", "PHOTOS")
        send("a", owner, "BEGIN", "DATE")
        send("a", owner, "BEGIN", "LOCATION:11")
        send("a", owner, "BEGIN", "PHOTO:88")
        `when`(locations.findAllByBundleIdOrderByPositionAscIdAsc(2)).thenReturn(emptyList())
        presence.changed(event(BundleEventType.LOCATION))
        assertThat(presence.get(10, 1, 2).editing.map { it.target })
            .containsExactly("DATE", "PHOTO:88", "PHOTOS")
        `when`(photos.findAllByBundleIdOrderByPositionAscIdAsc(2)).thenReturn(emptyList())
        presence.changed(event(BundleEventType.PHOTOS))
        assertThat(presence.get(10, 1, 2).editing.map { it.target }).containsExactly("DATE", "PHOTOS")
        assertThat(send("a", owner, "BACKGROUND").editing).isEmpty()
    }

    private fun send(connection: String, actor: BundleActor, action: String, target: String? = null, text: String? = null) =
        presence.command(actor, connection, 1, 2, BundlePresenceCommand(action, target, text))

    private fun event(type: BundleEventType) = BundleChangedEvent(1, 2, 0, "change", type, null)

    private class TestClock : Clock() {
        private var now = Instant.EPOCH
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }
}
