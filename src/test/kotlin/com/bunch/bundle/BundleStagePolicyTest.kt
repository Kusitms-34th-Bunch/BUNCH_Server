package com.bunch.bundle

import com.bunch.bundle.service.BundleStagePolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class BundleStagePolicyTest {
    @Test
    fun `four stages use configured inclusive thresholds`() {
        val policy = BundleStagePolicy(listOf(0, 2, 6, 10))
        val expected = mapOf(0L to 1, 1L to 1, 2L to 2, 5L to 2, 6L to 3, 9L to 3, 10L to 4, Long.MAX_VALUE to 4)
        expected.forEach { (count, stage) ->
            assertThat(policy.stage(count)).isEqualTo(stage)
        }
        val configured = BundleStagePolicy(listOf(0, 3, 7, 11))
        assertThat(configured.stage(2)).isEqualTo(1)
        assertThat(configured.stage(3)).isEqualTo(2)
        assertThat(configured.stage(7)).isEqualTo(3)
        assertThat(configured.stage(11)).isEqualTo(4)
    }

    @Test
    fun `invalid thresholds and negative publication counts are rejected`() {
        val invalid = listOf(
            emptyList(),
            listOf(0, 2, 6),
            listOf(0, 2, 6, 10, 14),
            listOf(1, 2, 6, 10),
            listOf(0, 2, 2, 10),
            listOf(0, 6, 2, 10),
            listOf(0, -1, 6, 10)
        )
        invalid.forEach { thresholds ->
            assertThatIllegalArgumentException().isThrownBy { BundleStagePolicy(thresholds.map(Int::toLong)) }
        }
        assertThatIllegalArgumentException().isThrownBy { BundleStagePolicy(listOf(0, 2, 6, 10)).stage(-1) }
    }
}
