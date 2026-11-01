package com.bunch.bundle.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class BundleStagePolicy(
    @Value("\${bunch.bundle.stage-thresholds:0,2,6,10}")
    private val thresholds: List<Long>
) {
    init {
        require(thresholds.size == 4 && thresholds.first() == 0L) {
            "Bundle stages require four thresholds starting at zero"
        }
        require(thresholds.zipWithNext().all { (lower, upper) -> lower < upper }) {
            "Bundle stage thresholds must be strictly increasing"
        }
    }

    fun stage(publishedCount: Long): Int {
        require(publishedCount >= 0)
        return thresholds.count { publishedCount >= it }
    }
}
