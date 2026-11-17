package com.bunch.recommendation.domain.combination

/** What a photo is mainly of (recommended bundle specification 3-6). A [DOCUMENT] is never a usable photo (3-1). */
enum class PhotoSubject {
    PERSON,
    FOOD,
    LANDSCAPE,
    OBJECT,
    ANIMAL,
    DOCUMENT
}
