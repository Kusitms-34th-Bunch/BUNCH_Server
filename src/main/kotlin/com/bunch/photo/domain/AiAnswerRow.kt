package com.bunch.photo.domain

/** The stored answer of the AI analysis of one photo, as the text of the JSON the model gave (specification 4-3). */
interface AiAnswerRow {
    val photoId: Long
    val raw: String
}
