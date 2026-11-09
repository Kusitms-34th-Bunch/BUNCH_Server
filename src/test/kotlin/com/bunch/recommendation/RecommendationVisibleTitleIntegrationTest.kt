package com.bunch.recommendation

import com.bunch.recommendation.domain.RecommendationSettings
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles

/**
 * Runs with no sentence left out for being used lately, so only the titles already in the list keep two candidates of
 * a bunch from getting the same title (4.2.2 rule 3-②).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RecommendationVisibleTitleIntegrationTest.NoRecentSentences::class)
class RecommendationVisibleTitleIntegrationTest : RecommendationIntegrationSupport() {
    @TestConfiguration
    class NoRecentSentences {
        @Bean
        @Primary
        fun settingsWithoutRecentSentences(): RecommendationSettings = RecommendationSettings(recentSentenceCount = 0)
    }

    @Test
    fun `two gatherings of one day do not get the same title`() {
        repeat(20) {
            val (ownerId, bunchId) = bunchWithOwner()
            val other = joined(ownerId, bunchId)
            shoot(bunchId, "2026-10-19", "13:00", 6, listOf(ownerId, other))
            shoot(bunchId, "2026-10-19", "20:00", 6, listOf(ownerId, other))

            recalculate(bunchId, "2026-10-19")

            assertTwoDifferentTitles(bunchId)
        }
    }

    @Test
    fun `a title that is already in the list is not made again for a new candidate on another day`() {
        repeat(20) {
            val (ownerId, bunchId) = bunchWithOwner()
            val other = joined(ownerId, bunchId)
            shoot(bunchId, "2026-10-19", "19:00", 6, listOf(ownerId, other))
            recalculate(bunchId, "2026-10-19")
            shoot(bunchId, "2026-10-20", "19:00", 6, listOf(ownerId, other))

            recalculate(bunchId, "2026-10-20")

            assertTwoDifferentTitles(bunchId)
        }
    }
}
