package com.bunch.recommendation.domain.combination

import java.security.MessageDigest
import java.time.LocalDate

/**
 * The key that tells a recommended bundle is the same one after the day is calculated again (recommended bundle
 * specification 10-3): the hash of the bunch, the day and the numbers of all the photos of the bundle, in order. A
 * bundle that has the same photos has the same key, so it keeps its number and the time it was made, and the list does
 * not flicker. A photo more or less makes another bundle.
 *
 * The key is made of the numbers of the photos only. The title, the card and the activity are not in it: they can
 * change when the analysis changes, and the bundle is still the one that was there.
 */
object BundleStableKey {
    fun of(bunchId: Long, dayKey: LocalDate, photoIds: Collection<Long>): String {
        val text = "$bunchId|$dayKey|${photoIds.sorted().joinToString(",")}"
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun of(bunchId: Long, bundle: RecoBundle): String = of(bunchId, bundle.dayKey, bundle.photos.map { it.photoId })
}
