package com.bunch.feed.service

import org.springframework.stereotype.Component
import java.util.random.RandomGenerator

@Component
class AdvertisementSelector {
    /** Uniform over the remaining pool after excluding the immediately previous advertisement. */
    fun select(
        candidates: List<Long>,
        slots: Int,
        lastAdId: Long?,
        random: RandomGenerator = RandomGenerator.getDefault()
    ): List<Long?> {
        require(slots >= 0)
        require(candidates.distinct().size == candidates.size)
        val pool = candidates.toMutableList()
        var previous = lastAdId
        return List(slots) {
            if (pool.isEmpty()) pool.addAll(candidates)
            val eligible = pool.filter { it != previous }
            if (eligible.isEmpty()) {
                null
            } else {
                eligible[random.nextInt(eligible.size)].also { selected ->
                    pool.remove(selected)
                    previous = selected
                }
            }
        }
    }
}
