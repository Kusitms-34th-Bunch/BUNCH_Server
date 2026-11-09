package com.bunch.social.dto

import com.bunch.bunch.domain.BunchReportReason

data class SocialContentReportRequest(val reason: BunchReportReason, val detail: String? = null)

data class SocialContentReportResult(val reportId: Long)
