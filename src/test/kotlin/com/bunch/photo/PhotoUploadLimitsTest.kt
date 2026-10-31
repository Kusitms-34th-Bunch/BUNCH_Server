package com.bunch.photo

import com.bunch.photo.service.PhotoUploadLimits
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
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
        assertThat(PhotoUploadLimits.MAX_PHOTOS_PER_MEMBER_PER_BUNCH_PER_DAY).isEqualTo(100)
        assertThat(PhotoUploadLimits.MAX_PHOTOS_PER_BUNCH).isEqualTo(1_000)
    }

    @Test
    fun `the request size limit admits a photo of the size limit the specification allows`() {
        val sources = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
        val multipart = Binder(ConfigurationPropertySources.from(sources))
            .bind("spring.servlet.multipart", MultipartProperties::class.java)
            .get()

        // The image check accepts up to 30 MiB (PhotoImageStorage); a smaller server limit would answer 400 first.
        assertThat(multipart.maxFileSize.toBytes()).isGreaterThanOrEqualTo(30L * 1024 * 1024)
        assertThat(multipart.maxRequestSize.toBytes()).isGreaterThan(multipart.maxFileSize.toBytes())
    }
}
