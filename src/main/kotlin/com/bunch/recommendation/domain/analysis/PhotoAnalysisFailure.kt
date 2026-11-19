package com.bunch.recommendation.domain.analysis

/**
 * A photo that could not be analyzed. The [reason] tells whether asking again can help. The message is only the name
 * of the reason: what the model service answered may carry the address or the key of the request, so it is never kept
 * here.
 */
class PhotoAnalysisFailure(
    val reason: Reason,
    cause: Throwable? = null
) : RuntimeException(reason.name, cause) {
    enum class Reason(val retryable: Boolean) {
        /** The model declined to answer. The same photo is declined again. */
        REFUSED(false),

        /** The model service did not accept the request, for example an image it cannot read. */
        BAD_REQUEST(false),

        /** The model answered, but not in the form that was asked for. */
        INVALID_RESPONSE(false),

        /** Too many requests were sent. A later request can succeed. */
        RATE_LIMITED(true),

        /** The model service did not answer in time or had an error of its own. A later request can succeed. */
        UNAVAILABLE(true),

        /** The key is missing or is not accepted. It is fixed in the settings, then a later request can succeed. */
        NOT_CONFIGURED(true)
    }
}
