package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.CardOrder
import com.bunch.recommendation.domain.combination.PreparedPhoto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The order of the pictures on the card (photo box specification 4.2.3 rule 1). */
class CardOrderTest {
    private val order = CardOrder()

    // A photo with a time on a date (the instant is the local time read as UTC), or with a date only.
    private fun timed(id: Long, dateTime: String): PreparedPhoto {
        val local = LocalDateTime.parse(dateTime)
        return PreparedPhoto(
            photo(id, date = local.toLocalDate().toString(), time = local.toLocalTime().toString()),
            LocalDate.parse("2026-10-19"),
            local,
            local.toInstant(ZoneOffset.UTC)
        )
    }

    private fun dateOnly(id: Long, date: String): PreparedPhoto =
        PreparedPhoto(photo(id, date = date), LocalDate.parse("2026-10-19"), null, null)

    private fun ids(photos: List<PreparedPhoto>) = photos.map { it.photoId }

    @Test
    fun `the photos go by the time they were taken, and the lower photo number goes first for the same time`() {
        val result = order.order(
            listOf(timed(3, "2026-10-19T12:00"), timed(2, "2026-10-19T09:00"), timed(1, "2026-10-19T12:00"))
        )

        assertThat(ids(result)).containsExactly(2L, 1L, 3L)
    }

    @Test
    fun `photos with a date only come after the photos with a time of the same date, by their numbers`() {
        val result = order.order(
            listOf(
                dateOnly(9, "2026-10-19"),
                timed(2, "2026-10-19T21:40"),
                dateOnly(7, "2026-10-19"),
                timed(1, "2026-10-19T19:02")
            )
        )

        assertThat(ids(result)).containsExactly(1L, 2L, 7L, 9L)
    }

    @Test
    fun `a bundle over midnight goes date by date, each date with its photos of a date only after its timed ones`() {
        val result = order.order(
            listOf(
                dateOnly(51, "2026-10-20"),
                timed(3, "2026-10-20T00:20"),
                dateOnly(50, "2026-10-19"),
                timed(2, "2026-10-19T23:40"),
                timed(1, "2026-10-19T23:10")
            )
        )

        assertThat(ids(result)).containsExactly(1L, 2L, 50L, 3L, 51L)
    }
}
