package com.bunch.global.exception
enum class ErrorCode(val code: String, val messageKey: String, val status: Int) {
    INVALID_INPUT("COMMON_001", "error.invalid_input", 400),
    INVALID_JSON("COMMON_002", "error.invalid_json", 400),
    INTERNAL_ERROR("COMMON_999", "error.internal_error", 500),
    RESOURCE_NOT_FOUND("RESOURCE_001", "error.resource_not_found", 404),
    USER_NOT_FOUND("USER_001", "error.user_not_found", 404),
    ALREADY_LOGGED_OUT("AUTH_007", "error.already_logged_out", 400),
    MALFORMED_JWT("AUTH_008", "error.malformed_jwt", 400),
    INVALID_SOCIAL_TOKEN("AUTH_009", "error.invalid_social_token", 401),
    NONCE_MISMATCH("AUTH_010", "error.nonce_mismatch", 401),
    TOKEN_REPLAY("AUTH_011", "error.token_replay", 401),
    UNAUTHORIZED("AUTH_001", "error.unauthorized", 401),
    FORBIDDEN("AUTH_002", "error.forbidden", 403),
    AUTH_PROVIDER_UNAVAILABLE("AUTH_014", "error.auth_provider_unavailable", 503)
}
