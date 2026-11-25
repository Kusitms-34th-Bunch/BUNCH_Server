package com.bunch.feed

import com.bunch.feed.service.AdvertisementSelector

/** Exercises the production default argument in a JVM with only java.base available. */
object AdvertisementRuntimeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val selector = AdvertisementSelector()
        check(selector.select(emptyList(), 3, null) == listOf(null, null, null))
        check(selector.select(listOf(7), 3, 7) == listOf(null, null, null))
        check(selector.select(listOf(7), 3, null) == listOf(7L, null, null))
        val candidates = listOf(1L, 2L, 3L)
        val selected = selector.select(candidates, 6, 1)
        check(selected.first() != 1L)
        check(selected.all { it in candidates })
        check(selected.zipWithNext().all { (left, right) -> left != right })
        check(selected.take(3).toSet() == candidates.toSet())
        check(selected.drop(3).toSet() == candidates.toSet())
        println("Advertisement runtime probe passed")
    }
}
