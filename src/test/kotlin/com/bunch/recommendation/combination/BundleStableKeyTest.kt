package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.BundleStableKey
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RuleTrace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The key that keeps a recommended bundle the same one when the day is calculated again (specification 10-3). */
class BundleStableKeyTest {
    private val day = LocalDate.parse("2026-10-19")

    @Test
    fun `the same bunch, day and photos give the same key, whatever the order of the photos`() {
        val key = BundleStableKey.of(7, day, listOf(3, 1, 2))

        assertThat(BundleStableKey.of(7, day, listOf(1, 2, 3))).isEqualTo(key)
        assertThat(BundleStableKey.of(7, day, listOf(2, 3, 1))).isEqualTo(key)
    }

    @Test
    fun `the key is a hash of 64 hexadecimal characters`() {
        assertThat(BundleStableKey.of(7, day, listOf(1, 2, 3))).matches("[0-9a-f]{64}")
    }

    @Test
    fun `a photo more or less is another bundle`() {
        val key = BundleStableKey.of(7, day, listOf(1, 2, 3))

        assertThat(BundleStableKey.of(7, day, listOf(1, 2, 3, 4))).isNotEqualTo(key)
        assertThat(BundleStableKey.of(7, day, listOf(1, 2))).isNotEqualTo(key)
        assertThat(BundleStableKey.of(7, day, listOf(1, 2, 4))).isNotEqualTo(key)
    }

    @Test
    fun `another day or another bunch is another bundle`() {
        val key = BundleStableKey.of(7, day, listOf(1, 2, 3))

        assertThat(BundleStableKey.of(7, day.plusDays(1), listOf(1, 2, 3))).isNotEqualTo(key)
        assertThat(BundleStableKey.of(8, day, listOf(1, 2, 3))).isNotEqualTo(key)
    }

    @Test
    fun `the numbers are not run together, so 1 and 23 is not 12 and 3`() {
        assertThat(BundleStableKey.of(7, day, listOf(1, 23))).isNotEqualTo(BundleStableKey.of(7, day, listOf(12, 3)))
        assertThat(BundleStableKey.of(1, day, listOf(23))).isNotEqualTo(BundleStableKey.of(12, day, listOf(3)))
    }

    @Test
    fun `the key of a bundle is the key of all its photos, the ones with a date only too`() {
        val bundle = bundleOf(
            RecoBundle.BundlePhoto(2, PhotoRole.GENERAL),
            RecoBundle.BundlePhoto(1, PhotoRole.GENERAL),
            RecoBundle.BundlePhoto(9, PhotoRole.ATTACHED_DATE_ONLY)
        )

        assertThat(BundleStableKey.of(7, bundle)).isEqualTo(BundleStableKey.of(7, day, listOf(1, 2, 9)))
    }

    @Test
    fun `nothing but the photos is in the key, so a new title or card keeps the bundle`() {
        val bundle = bundleOf(
            RecoBundle.BundlePhoto(1, PhotoRole.GENERAL),
            RecoBundle.BundlePhoto(2, PhotoRole.GENERAL)
        )

        val named = bundle.copy(title = "10월 19일 밤 식사", coverPhotoId = 2)

        assertThat(BundleStableKey.of(7, named)).isEqualTo(BundleStableKey.of(7, bundle))
    }

    private fun bundleOf(vararg photos: RecoBundle.BundlePhoto) = RecoBundle(
        dayKey = day,
        kind = BundleKind.GENERAL,
        eventType = null,
        establishedBy = null,
        activity = null,
        subIndex = null,
        photos = photos.toList(),
        coverPhotoId = null,
        startAt = Instant.parse("2026-10-19T10:00:00Z"),
        endAt = Instant.parse("2026-10-19T11:00:00Z"),
        photoCount = photos.size,
        uploaderCount = 2,
        hasGroupPhoto = false,
        trace = RuleTrace(day, BundleKind.GENERAL, RuleTrace.Counts(photos.size, 2), "reco-cfg-1")
    )
}
