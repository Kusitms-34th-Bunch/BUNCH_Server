package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RuleTrace
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class RecoBundleTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val start = Instant.parse("2026-10-19T10:00:00Z")
    private val end = Instant.parse("2026-10-19T13:00:00Z")

    @Test
    fun `an event bundle has an event type and what made it stand`() {
        val bundle = bundle(
            kind = BundleKind.EVENT,
            eventType = EventType.BIRTHDAY,
            establishedBy = EstablishedBy.STRONG
        )

        assertThat(bundle.eventType).isEqualTo(EventType.BIRTHDAY)
        assertThat(bundle.establishedBy).isEqualTo(EstablishedBy.STRONG)
    }

    @Test
    fun `an event bundle without an event type or without what made it stand is refused`() {
        assertThatThrownBy { bundle(kind = BundleKind.EVENT, eventType = null, establishedBy = EstablishedBy.STRONG) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bundle(kind = BundleKind.EVENT, eventType = EventType.BIRTHDAY, establishedBy = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bundle(kind = BundleKind.EVENT, eventType = null, establishedBy = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a general bundle has neither an event type nor what made an event stand`() {
        assertThat(bundle(kind = BundleKind.GENERAL).eventType).isNull()
        assertThatThrownBy { bundle(kind = BundleKind.GENERAL, eventType = EventType.BIRTHDAY) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bundle(kind = BundleKind.GENERAL, establishedBy = EstablishedBy.SUPPORT) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the part of a day starts from 1`() {
        assertThat(bundle(subIndex = 1).subIndex).isEqualTo(1)
        assertThatThrownBy { bundle(subIndex = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a bundle does not end before it starts`() {
        assertThatThrownBy { bundle(startAt = end, endAt = start) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a place on the card is from 1 and is used once`() {
        val first = RecoBundle.BundlePhoto(photoId = 1L, role = PhotoRole.GENERAL, cardPosition = 1)
        val second = RecoBundle.BundlePhoto(photoId = 2L, role = PhotoRole.GENERAL, cardPosition = 2)
        val off = RecoBundle.BundlePhoto(photoId = 3L, role = PhotoRole.GENERAL)

        assertThat(bundle(photos = listOf(first, second, off)).photos).hasSize(3)
        assertThatThrownBy { bundle(photos = listOf(first, first.copy(photoId = 9L))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bundle(photos = listOf(first.copy(cardPosition = 0))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the cover is a photo of the bundle, or not chosen yet`() {
        val photo = RecoBundle.BundlePhoto(photoId = 1L, role = PhotoRole.GENERAL, cardPosition = 1)

        assertThat(bundle(photos = listOf(photo), coverPhotoId = 1L).coverPhotoId).isEqualTo(1L)
        assertThat(bundle(photos = listOf(photo), coverPhotoId = null).coverPhotoId).isNull()
        assertThatThrownBy { bundle(photos = listOf(photo), coverPhotoId = 99L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a bundle has no title until it is named, and a title is not blank`() {
        assertThat(bundle().title).isNull()
        assertThat(bundle().copy(title = "10월 19일 밤 식사").title).isEqualTo("10월 19일 밤 식사")
        listOf("", " ", "\n").forEach { blank ->
            assertThatThrownBy { bundle().copy(title = blank) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the counts are not negative`() {
        assertThatThrownBy { bundle(photoCount = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { bundle(uploaderCount = -1) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bundle(
        kind: BundleKind = BundleKind.GENERAL,
        eventType: EventType? = null,
        establishedBy: EstablishedBy? = null,
        subIndex: Int? = null,
        photos: List<RecoBundle.BundlePhoto> = emptyList(),
        coverPhotoId: Long? = null,
        startAt: Instant = start,
        endAt: Instant = end,
        photoCount: Int = 6,
        uploaderCount: Int = 2
    ) = RecoBundle(
        dayKey = day,
        kind = kind,
        eventType = eventType,
        establishedBy = establishedBy,
        activity = null,
        subIndex = subIndex,
        photos = photos,
        coverPhotoId = coverPhotoId,
        startAt = startAt,
        endAt = endAt,
        photoCount = photoCount,
        uploaderCount = uploaderCount,
        hasGroupPhoto = false,
        trace = RuleTrace(
            dayKey = day,
            kind = kind,
            counts = RuleTrace.Counts(photos = photoCount, uploaders = uploaderCount),
            configVersion = "reco-cfg-1"
        )
    )
}
