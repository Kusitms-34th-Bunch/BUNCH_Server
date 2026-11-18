package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.clued
import com.bunch.recommendation.combination.EventTestSupport.friends
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventBundleBuilder
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventFinder
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The bundle of an event that stood (recommended bundle specification 4-5, 6-4, 11). */
class EventBundleBuilderTest {
    private fun birthday(cake: RecoPhoto = cakePhoto()): RecoBundle {
        val day = ready(listOf(cake) + friends(10, "20:10", 6))
        return EventBundleBuilder().build(day, EventFinder().find(day).confirmed.single())
    }

    private fun cakePhoto() = clued(1, "20:00", 1, CANDLE_WHOLE_CAKE, person = true)

    @Test
    fun `the bundle is an event of the kind and the way it stood`() {
        val bundle = birthday()

        assertThat(bundle.kind).isEqualTo(BundleKind.EVENT)
        assertThat(bundle.eventType).isEqualTo(EventType.BIRTHDAY)
        assertThat(bundle.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(bundle.activity).isNull()
        assertThat(bundle.subIndex).isNull()
        assertThat(bundle.coverPhotoId).isNull()
    }

    @Test
    fun `a photo that shows a clue of the event is a clue and the others of the bundle are related`() {
        val bundle = birthday()

        assertThat(bundle.photos.first { it.photoId == 1L }.role).isEqualTo(PhotoRole.CLUE)
        assertThat(bundle.photos.filter { it.photoId != 1L }.map { it.role }).containsOnly(PhotoRole.RELATED)
        assertThat(bundle.photos).hasSize(7)
    }

    @Test
    fun `the bundle lasts from its first photo to its last, not over the range of the event`() {
        val bundle = birthday()

        assertThat(bundle.startAt).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"))
        assertThat(bundle.endAt).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"))
    }

    @Test
    fun `the pictures and the members are counted, and a group photo is noticed`() {
        val bundle = birthday()

        assertThat(bundle.photoCount).isEqualTo(7)
        assertThat(bundle.uploaderCount).isEqualTo(2)
        assertThat(bundle.hasGroupPhoto).isTrue()
        assertThat(bundle.trace.counts.photos).isEqualTo(7)
        assertThat(bundle.trace.counts.uploaders).isEqualTo(2)
    }

    @Test
    fun `a clue photo of low quality is in the bundle and is not counted`() {
        val bundle = birthday(cakePhoto().copy(quality = PhotoQuality.LOW))

        assertThat(bundle.photos).hasSize(7)
        assertThat(bundle.photoCount).isEqualTo(6)
    }

    @Test
    fun `the trace tells how the event stood`() {
        val trace = birthday().trace

        assertThat(trace.kind).isEqualTo(BundleKind.EVENT)
        assertThat(trace.configVersion).isEqualTo("reco-cfg-1")
        assertThat(trace.general).isNull()
        assertThat(trace.selection).isNull()
        val event = trace.event!!
        assertThat(event.type).isEqualTo(EventType.BIRTHDAY)
        assertThat(event.establishedBy).isEqualTo(EstablishedBy.STRONG)
        assertThat(event.strongPhotoIds).containsExactly(1L)
        assertThat(event.core.start).isEqualTo(Instant.parse("2026-10-19T11:00:00Z"))
    }
}
