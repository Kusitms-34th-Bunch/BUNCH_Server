package com.bunch.bunch.domain

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

object BunchReportPolicy {
    private val grapheme = Pattern.compile("\\X")

    fun detail(reason: BunchReportReason, value: String?): String? {
        val detail = value?.trim()?.takeIf { it.isNotEmpty() }
        if ((detail != null && grapheme.matcher(detail).results().count() > 200) ||
            (reason == BunchReportReason.OTHER && detail.isNullOrBlank())
        ) throw BusinessException(ErrorCode.INVALID_INPUT)
        return detail
    }

    fun windowStartedAt(now: Instant, latest: Instant?): Instant =
        latest?.takeIf { now.isBefore(it.plus(24, ChronoUnit.HOURS)) } ?: now

    // Call under the reporter Bunch write lock, keeping the check and insert in the same transaction.
    fun requireAvailable(acceptedCount: Long) {
        if (acceptedCount >= 2) throw BusinessException(ErrorCode.BUNCH_REPORT_LIMIT_EXCEEDED)
    }
}
