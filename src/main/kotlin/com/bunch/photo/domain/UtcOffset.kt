package com.bunch.photo.domain

/** A UTC offset as the app sends it: `±HH:mm`, from -12:00 to +14:00 (recommended bundle specification 5-2). */
object UtcOffset {
    private val FORMAT = Regex("[+-][0-9]{2}:[0-5][0-9]")
    private const val MIN_MINUTES = -12 * 60
    private const val MAX_MINUTES = 14 * 60

    fun isValid(value: String): Boolean {
        if (!FORMAT.matches(value)) return false

        val hours = value.substring(1, 3).toInt()
        val minutes = value.substring(4, 6).toInt()
        val totalMinutes = hours * 60 + minutes
        val signedMinutes = if (value[0] == '-') -totalMinutes else totalMinutes
        return signedMinutes in MIN_MINUTES..MAX_MINUTES
    }
}
