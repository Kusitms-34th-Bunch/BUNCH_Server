package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.AiStatus
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.FingerprintSimilarity
import com.bunch.recommendation.domain.combination.ImageSimilarity
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.util.UUID

/** The similarity of two photos from their image fingerprints (recommended bundle specification 5-4, 12). */
class ImageSimilarityTest {
    private val settings = CombinationSettings()

    @Test
    fun `the same fingerprint is the same image and all bits apart is nothing alike`() {
        assertThat(FingerprintSimilarity.similarity(photo(0L), photo(0L))).isEqualTo(1.0)
        assertThat(FingerprintSimilarity.similarity(photo(-1L), photo(-1L))).isEqualTo(1.0)
        assertThat(FingerprintSimilarity.similarity(photo(0L), photo(-1L))).isEqualTo(0.0)
    }

    @Test
    fun `the similarity is the share of equal bits and does not depend on the order`() {
        val first = photo(0L)
        val second = photo(0b1111L)

        assertThat(FingerprintSimilarity.similarity(first, second)).isEqualTo(1.0 - 4.0 / 64)
        assertThat(FingerprintSimilarity.similarity(second, first)).isEqualTo(1.0 - 4.0 / 64)
    }

    @Test
    fun `a photo without a fingerprint cannot be told alike, to another one or to itself`() {
        val known = photo(0L)
        val unknown = photo(null)

        assertThat(FingerprintSimilarity.similarity(known, unknown)).isNull()
        assertThat(FingerprintSimilarity.similarity(unknown, known)).isNull()
        assertThat(FingerprintSimilarity.similarity(unknown, unknown)).isNull()
    }

    @ParameterizedTest
    @CsvSource("0,true", "5,true", "6,true", "7,false", "10,false", "64,false")
    fun `six different bits are still a burst and seven are not`(differentBits: Int, burst: Boolean) {
        val similarity = FingerprintSimilarity.similarity(photo(0L), photo(bitsApart(differentBits)))!!

        assertThat(similarity >= settings.fingerprintBurstMinSimilarity).isEqualTo(burst)
    }

    @ParameterizedTest
    @CsvSource("0,true", "2,true", "3,true", "4,false", "6,false", "64,false")
    fun `three different bits are still the same composition and four are not`(differentBits: Int, same: Boolean) {
        val similarity = FingerprintSimilarity.similarity(photo(0L), photo(bitsApart(differentBits)))!!

        assertThat(similarity >= settings.fingerprintSameCompositionMinSimilarity).isEqualTo(same)
    }

    @Test
    fun `the similarity can be asked without knowing what it is made of`() {
        val ofAnything = ImageSimilarity { _, _ -> 0.5 }

        assertThat(ofAnything.similarity(photo(0L), photo(-1L))).isEqualTo(0.5)
    }

    // A value that differs from 0 in exactly this many bits (the lowest ones).
    private fun bitsApart(count: Int): Long = if (count >= Long.SIZE_BITS) -1L else (1L shl count) - 1

    private fun photo(fingerprint: Long?) = RecoPhoto(
        photoId = 1L,
        capturedDate = null,
        capturedTime = null,
        utcOffset = null,
        uploads = listOf(RecoPhoto.Upload(memberId = 1L, uploadBatchId = UUID.randomUUID())),
        uploadedAt = Instant.parse("2026-10-20T00:00:00Z"),
        source = SourceType.CAMERA,
        quality = null,
        aiStatus = AiStatus.DONE,
        fingerprint = fingerprint
    )
}
