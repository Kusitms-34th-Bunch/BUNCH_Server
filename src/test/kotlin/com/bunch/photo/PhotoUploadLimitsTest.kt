package com.bunch.photo

import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class PhotoUploadLimitsTest {
    @Test
    fun `the upload day starts at midnight in Korean time`() {
        val midnight = Instant.parse("2026-10-28T15:00:00Z")

        assertThat(PhotoUploadLimits.startOfUploadDay(Instant.parse("2026-10-29T00:00:00Z"))).isEqualTo(midnight)
        assertThat(PhotoUploadLimits.startOfUploadDay(Instant.parse("2026-10-29T14:59:59Z"))).isEqualTo(midnight)
    }

    @Test
    fun `a new Korean day begins exactly at 15 00 UTC`() {
        val next = Instant.parse("2026-10-29T15:00:00Z")

        assertThat(PhotoUploadLimits.startOfUploadDay(next)).isEqualTo(next)
        assertThat(PhotoUploadLimits.startOfUploadDay(Instant.parse("2026-10-29T15:00:01Z"))).isEqualTo(next)
    }

    @Test
    fun `the limits match the feature specification`() {
        assertThat(PhotoUploadLimits.MAX_PHOTOS_PER_BATCH).isEqualTo(50)
        assertThat(PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_DAY).isEqualTo(500)
        assertThat(PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH).isEqualTo(1_000)
    }
}
