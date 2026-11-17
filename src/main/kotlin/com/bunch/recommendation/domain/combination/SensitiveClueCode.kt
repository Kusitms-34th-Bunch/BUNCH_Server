package com.bunch.recommendation.domain.combination

/**
 * What a photo shows that stops the recommendation of the whole day (recommended bundle specification 3-2 and 5-6):
 * a funeral or a hospital. They are kept apart from [EventClueCode] because they never make an event.
 */
enum class SensitiveClueCode {
    FUNERAL_WREATH,
    MOURNING_CLOTHES,
    FUNERAL_ALTAR,
    MEMORIAL_PORTRAIT,
    HOSPITAL_ROOM,
    HOSPITAL_BED,
    IV_DRIP,
    SURGICAL_GOWN
}
