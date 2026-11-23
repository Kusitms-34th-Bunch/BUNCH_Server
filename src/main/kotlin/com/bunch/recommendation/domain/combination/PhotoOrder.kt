package com.bunch.recommendation.domain.combination

import java.time.Instant

/**
 * The one order every step puts photos in, so that the same photos always give the same bundles (recommended bundle
 * specification 1.5 and 1.8): the earlier time first, and when the times are the same the lower photo ID. A photo
 * without a time comes after all that have one, again by photo ID (8-6, where the order of the upload is read as the
 * order of the IDs).
 *
 * The time is given by the caller, because the steps do not all use the same clock: the local one or the absolute one.
 */
object PhotoOrder {
    fun <T> byTimeThenId(timeOf: (T) -> Instant?, idOf: (T) -> Long): Comparator<T> =
        compareBy<T, Instant?>(nullsLast(), timeOf).thenBy(idOf)
}
