package com.bunch.recommendation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecommendationRecalculationIntegrationTest : RecommendationIntegrationSupport() {
    private val oldAddedAt = "2026-10-01T10:00:00+09:00"

    @Test
    fun `a gathering of two members becomes a candidate with a title and all its photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))

        recalculate(bunchId, "2026-10-19")

        val candidate = candidates(bunchId).single()
        assertThat(candidate.startDate).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(candidate.endDate).isNull()
        assertThat(candidate.title).isNotBlank()
        assertThat(candidate.title.length).isLessThanOrEqualTo(15)
        assertThat(candidate.sentenceKey).startsWith("first.")
        assertThat(photosOf(candidate.id)).containsExactlyElementsOf(photos.mapIndexed { index, id -> id to index + 1 })
        assertThat(candidate.lastPhotoAddedAt).isEqualTo(latestAddedAt(bunchId))
    }

    @Test
    fun `a gathering of 12 photos keeps all of them and shows 10 in order`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 12, listOf(ownerId, other))

        recalculate(bunchId, "2026-10-19")

        val photos = photosOf(candidates(bunchId).single().id)
        assertThat(photos).hasSize(12)
        assertThat(photos.mapNotNull { it.second }).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    }

    @Test
    fun `a card always has six to ten photos whatever the size of the gathering`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val sizes = mapOf("2026-10-19" to 6, "2026-10-21" to 10, "2026-10-23" to 11, "2026-10-25" to 25)
        sizes.forEach { (date, count) -> shoot(bunchId, date, "10:00", count, listOf(ownerId, other)) }

        recalculate(bunchId, *sizes.keys.toTypedArray())

        val stored = candidates(bunchId).map { photosOf(it.id) }
        assertThat(stored.map { it.size }).containsExactly(6, 10, 11, 25)
        assertThat(stored.map { photos -> photos.count { it.second != null } }).containsExactly(6, 10, 10, 10)
    }

    @Test
    fun `photos of one member or fewer than six photos do not become a candidate`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId))
        shoot(bunchId, "2026-10-25", "19:00", 5, listOf(ownerId, other))

        recalculate(bunchId, "2026-10-19", "2026-10-25")

        assertThat(candidates(bunchId)).isEmpty()
    }

    @Test
    fun `calculating again changes nothing`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId).single()

        recalculate(bunchId, "2026-10-19")

        val after = candidates(bunchId).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.title).isEqualTo(before.title)
        assertThat(after.sentenceKey).isEqualTo(before.sentenceKey)
        assertThat(after.lastPhotoAddedAt).isEqualTo(before.lastPhotoAddedAt)
        assertThat(photosOf(after.id)).hasSize(8)
    }

    @Test
    fun `a candidate that got new photos keeps its title and its time moves to the new photos`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val third = joined(ownerId, bunchId)
        val first = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        first.forEach { setAddedAt(it, oldAddedAt) }
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId).single()
        assertThat(before.lastPhotoAddedAt).isEqualTo(Instant.parse("2026-10-01T01:00:00Z"))

        shoot(bunchId, "2026-10-19", "19:05", 5, listOf(third))
        recalculate(bunchId, "2026-10-19")

        val after = candidates(bunchId).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.title).isEqualTo(before.title)
        assertThat(after.sentenceKey).isEqualTo(before.sentenceKey)
        assertThat(photosOf(after.id)).hasSize(13)
        assertThat(photosOf(after.id).count { it.second != null }).isEqualTo(10)
        assertThat(after.lastPhotoAddedAt).isAfter(before.lastPhotoAddedAt)
        assertThat(after.lastPhotoAddedAt).isEqualTo(latestAddedAt(bunchId))
    }

    @Test
    fun `deleting photos keeps the title and the time of the last photo added`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        photos.forEach { setAddedAt(it, oldAddedAt) }
        setAddedAt(photos.last(), "2026-10-05T10:00:00+09:00")
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId).single()
        assertThat(before.lastPhotoAddedAt).isEqualTo(Instant.parse("2026-10-05T01:00:00Z"))

        jdbc.update("DELETE FROM photos WHERE id = ?", photos.last())
        recalculate(bunchId, "2026-10-19")

        val after = candidates(bunchId).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.title).isEqualTo(before.title)
        assertThat(after.lastPhotoAddedAt).isEqualTo(before.lastPhotoAddedAt)
        assertThat(photosOf(after.id)).hasSize(7)
    }

    @Test
    fun `a candidate that no longer has six photos ends`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")

        photos.take(3).forEach { jdbc.update("DELETE FROM photos WHERE id = ?", it) }
        recalculate(bunchId, "2026-10-19")

        assertThat(candidates(bunchId)).isEmpty()
        assertThat(count("candidate_photos")).isZero()
    }

    @Test
    fun `a candidate that is split into two gatherings ends and both become new candidates with other titles`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "13:00", 6, listOf(ownerId, other))
        val bridge = insertPhoto(bunchId, "2026-10-19", "17:00", uploaders = listOf(ownerId))
        shoot(bunchId, "2026-10-19", "20:00", 6, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId).single()

        jdbc.update("DELETE FROM photos WHERE id = ?", bridge)
        recalculate(bunchId, "2026-10-19")

        val after = candidates(bunchId)
        assertThat(after).hasSize(2)
        assertThat(after.map { it.id }).doesNotContain(before.id)
        assertThat(after.map { it.title }.toSet()).hasSize(2)
        assertThat(after.map { photosOf(it.id).size }).containsExactly(6, 6)
    }

    @Test
    fun `two candidates that merge into one gathering end and one new candidate is made`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "13:00", 6, listOf(ownerId, other))
        shoot(bunchId, "2026-10-19", "20:00", 6, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId)
        assertThat(before).hasSize(2)

        insertPhoto(bunchId, "2026-10-19", "17:00", uploaders = listOf(ownerId))
        recalculate(bunchId, "2026-10-19")

        val after = candidates(bunchId).single()
        assertThat(before.map { it.id }).doesNotContain(after.id)
        assertThat(photosOf(after.id)).hasSize(13)
    }

    @Test
    fun `two gatherings of one day get different titles`() {
        repeat(8) {
            val (ownerId, bunchId) = bunchWithOwner()
            val other = joined(ownerId, bunchId)
            shoot(bunchId, "2026-10-19", "13:00", 6, listOf(ownerId, other))
            shoot(bunchId, "2026-10-19", "20:00", 6, listOf(ownerId, other))

            recalculate(bunchId, "2026-10-19")

            assertTwoDifferentTitles(bunchId)
        }
    }

    @Test
    fun `photos a bundle used still count when the gathering is judged the first one`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val used = shoot(bunchId, "2026-10-01", "19:00", 6, listOf(ownerId, other))
        bundleWith(bunchId, ownerId, "PUBLISHED", *used.toLongArray())
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))

        recalculate(bunchId, "2026-10-01", "2026-10-19")

        val candidate = candidates(bunchId).single()
        assertThat(candidate.startDate).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(candidate.sentenceKey).startsWith("default.")
    }

    @Test
    fun `photos a bundle used still count when the gathering is judged a long time after the last one`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val used = shoot(bunchId, "2026-08-20", "19:00", 6, listOf(ownerId, other))
        bundleWith(bunchId, ownerId, "PUBLISHED", *used.toLongArray())
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))

        recalculate(bunchId, "2026-10-19")

        assertThat(candidates(bunchId).single().sentenceKey).startsWith("long_gap.")
    }

    @Test
    fun `photos a draft took leave the candidate and it stays while enough photos are left`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId).single()

        val draft = bundleWith(bunchId, ownerId, "DRAFT", photos[0], photos[1])
        recalculate(bunchId, "2026-10-19")

        val after = candidates(bunchId).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.title).isEqualTo(before.title)
        assertThat(photosOf(after.id).map { it.first }).containsExactlyInAnyOrderElementsOf(photos.drop(2))

        deleteBundle(draft)
        recalculate(bunchId, "2026-10-19")

        assertThat(candidates(bunchId).single().id).isEqualTo(before.id)
        assertThat(photosOf(before.id)).hasSize(8)
    }

    @Test
    fun `a draft that leaves fewer than six photos ends the candidate`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        val photos = shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")

        bundleWith(bunchId, ownerId, "DRAFT", photos[0], photos[1], photos[2])
        recalculate(bunchId, "2026-10-19")

        assertThat(candidates(bunchId)).isEmpty()
    }

    @Test
    fun `only the changed date and the day before and after are calculated`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        listOf("2026-10-17", "2026-10-18", "2026-10-20", "2026-10-21").forEach {
            shoot(bunchId, it, "19:00", 6, listOf(ownerId, other))
        }

        recalculate(bunchId, "2026-10-19")

        assertThat(candidates(bunchId).map { it.startDate })
            .containsExactly(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 20))
    }

    @Test
    fun `a candidate on a date that did not change is left as it is`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        shoot(bunchId, "2026-10-25", "19:00", 8, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19", "2026-10-25")
        val far = candidates(bunchId).last()

        shoot(bunchId, "2026-10-25", "20:30", 3, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        assertThat(photosOf(far.id)).hasSize(8)

        recalculate(bunchId, "2026-10-25")
        assertThat(photosOf(far.id)).hasSize(11)
    }

    @Test
    fun `a candidate over midnight is seen whole when only its second day changed`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "23:10", 4, listOf(ownerId, other))
        shoot(bunchId, "2026-10-20", "00:10", 4, listOf(ownerId, other))
        recalculate(bunchId, "2026-10-19")
        val before = candidates(bunchId).single()
        assertThat(before.startDate).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(before.endDate).isEqualTo(LocalDate.of(2026, 10, 20))

        insertPhoto(bunchId, "2026-10-20", "00:50", uploaders = listOf(ownerId))
        recalculate(bunchId, "2026-10-20")

        val after = candidates(bunchId).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.startDate).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(after.endDate).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(photosOf(after.id)).hasSize(9)
    }

    @Test
    fun `calculating at the same time makes one candidate`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        val runs = (1..threads).map {
            pool.submit {
                start.await()
                recalculate(bunchId, "2026-10-19")
            }
        }
        start.countDown()
        runs.forEach { it.get() }
        pool.shutdown()

        assertThat(candidates(bunchId)).hasSize(1)
        assertThat(count("candidate_photos")).isEqualTo(8)
    }

    @Test
    fun `nothing changes without a date or for a bunch that does not exist`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val other = joined(ownerId, bunchId)
        shoot(bunchId, "2026-10-19", "19:00", 8, listOf(ownerId, other))

        recommendation.recalculate(bunchId, emptySet())
        recommendation.recalculate(Long.MAX_VALUE, setOf(LocalDate.of(2026, 10, 19)))

        assertThat(candidates(bunchId)).isEmpty()
    }
}
