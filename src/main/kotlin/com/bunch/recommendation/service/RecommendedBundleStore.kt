package com.bunch.recommendation.service

import com.bunch.recommendation.domain.RecommendedBundle
import com.bunch.recommendation.domain.RecommendedBundlePhoto
import com.bunch.recommendation.domain.RecommendedBundlePhotoRepository
import com.bunch.recommendation.domain.RecommendedBundleRepository
import com.bunch.recommendation.domain.combination.BundleStableKey
import com.bunch.recommendation.domain.combination.BundleVisibility
import com.bunch.recommendation.domain.combination.CombinationResult
import com.bunch.recommendation.domain.combination.DayCombination
import com.bunch.recommendation.domain.combination.RecoBundle
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * Saves the calculation of a bunch as its recommended bundles (recommended bundle specification 10-3). The recommended
 * bundles of the bunch are made the ones the calculation gives, in one transaction, so the bunch is never seen half
 * changed:
 *
 * - A bundle that has the same photos has the same [BundleStableKey]. It is **kept** with its number, the time it was
 *   made and the time it is shown from, so the list does not flicker and a bundle is not shown again. What the
 *   calculation gives again (the title, the activity, the counts, the trace, the card) is written over, and only when
 *   it is not the same.
 * - A bundle that is not in the calculation any more is **deleted** and not recorded.
 * - A bundle that is new is **added**, to be shown from the later of now and the end of its day (10-4).
 * - The bundles of a day that is **blocked** (a sensitive clue) or that has nothing to recommend are deleted, and so
 *   are the bundles of a day that is not in the calculation any more (all its photos are gone).
 * - The bundles of a day that is **held** (photos still being analyzed) stay as they are (5-6).
 *
 * The whole bunch is saved, not only the days that changed: the calculation reads all the photos of the bunch, and a
 * day can change because of another (an upload batch with a wrong clock is left out of every day, X5). A bundle that
 * did not change is left as it is, so this costs a comparison, not a write.
 *
 * A photo is in one recommended bundle only (7-10), so the rows of the photos that leave a bundle are deleted before
 * the rows of the bundles they come to are written. A photo that is still in a bundle of a day that is held, and
 * belongs to a bundle of another day now, is taken out of the held bundle, and its day is told in
 * [RecommendedBundleStoreOutcome.staleDays] to be calculated again.
 *
 * A new bundle remembers when the last of its photos was added to the photo box ([photoAddedAt], by photo number, the
 * `created_at` of the photos): it is no longer shown 30 days after that day (4.2.9 rule 4, [BundleExpiry]). A photo
 * that is not in [photoAddedAt] counts as added when the calculation was made. A bundle that is kept has the same
 * photos, so it has the same time and is not written again.
 *
 * Two calculations of a bunch must not save at the same time: the caller holds the lock of the bunch while it
 * calculates and saves, as the photo changes do.
 */
@Service
class RecommendedBundleStore(
    private val bundles: RecommendedBundleRepository,
    private val photos: RecommendedBundlePhotoRepository,
    private val visibility: BundleVisibility,
    private val mapper: ObjectMapper
) {
    // The trace in a form that does not depend on the order of the keys, so the same trace is the same when it is read
    // back from the database, whose JSON does not keep the order.
    private val canonical = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    private val traceType = object : TypeReference<Map<String, Any?>>() {}

    @Transactional
    fun replace(
        bunchId: Long,
        result: CombinationResult,
        calculatedAt: Instant,
        photoAddedAt: Map<Long, Instant> = emptyMap()
    ): RecommendedBundleStoreOutcome {
        val heldDays = result.days.filterIsInstance<DayCombination.Held>().map { it.dayKey }.toSet()
        val desired = desiredOf(bunchId, result)
        val stored = bundles.findByBunchId(bunchId).filter { it.dayKey !in heldDays }
        val storedKeys = stored.map { it.stableKey }.toSet()

        val removed = stored.filter { it.stableKey !in desired }
        val kept = stored.filter { it.stableKey in desired }
        val added = desired.filterKeys { it !in storedKeys }.values.toList()

        if (removed.isNotEmpty()) bundles.deleteAllByIdInBatch(removed.map { it.id })

        val keptRows = photos.findByBundleIdIn(kept.map { it.id }).groupBy { it.bundleId }
        var updated = 0
        val rewritten = mutableListOf<Pair<RecommendedBundle, RecoBundle>>()
        kept.forEach { entity ->
            val bundle = desired.getValue(entity.stableKey)
            val fieldsChanged = write(entity, bundle, calculatedAt)
            val rowsChanged = rowsOf(keptRows[entity.id].orEmpty()) != rowsOf(bundle)
            if (rowsChanged) {
                photos.deleteAllByIdInBatch(keptRows.getValue(entity.id).map { it.id })
                rewritten += entity to bundle
            }
            if (fieldsChanged || rowsChanged) updated++
        }

        val created = bundles.saveAll(added.map { newEntity(bunchId, it, calculatedAt, photoAddedAt) })
        val rows = rewritten.flatMap { (entity, bundle) -> rowsFor(entity.id, bundle) } +
            created.zip(added).flatMap { (entity, bundle) -> rowsFor(entity.id, bundle) }
        val staleDays = takeOutOfHeldDays(rows, heldDays)
        photos.saveAll(rows)
        bundles.flush()

        return RecommendedBundleStoreOutcome(
            created = created.size,
            updated = updated,
            unchanged = kept.size - updated,
            removed = removed.size,
            staleDays = staleDays
        )
    }

    // The bundles the calculation gives, by their stable key. Only a day that stands has bundles: a day that is
    // blocked or has none gives nothing, and what it had is deleted by being missing here.
    private fun desiredOf(bunchId: Long, result: CombinationResult): Map<String, RecoBundle> {
        val all = result.days.filterIsInstance<DayCombination.Bundled>().flatMap { it.bundles }
        val byKey = all.associateBy { BundleStableKey.of(bunchId, it) }
        check(byKey.size == all.size) { "two bundles of the calculation have the same photos" }
        val photoIds = all.flatMap { bundle -> bundle.photos.map { it.photoId } }
        check(photoIds.size == photoIds.toSet().size) { "a photo is in two bundles of the calculation (7-10)" }
        return byKey
    }

    private fun newEntity(
        bunchId: Long,
        bundle: RecoBundle,
        calculatedAt: Instant,
        photoAddedAt: Map<Long, Instant>
    ) = RecommendedBundle(
        bunchId = bunchId,
        dayKey = bundle.dayKey,
        stableKey = BundleStableKey.of(bunchId, bundle),
        kind = bundle.kind,
        eventType = bundle.eventType,
        establishedBy = bundle.establishedBy,
        activity = bundle.activity,
        subIndex = bundle.subIndex,
        title = titleOf(bundle),
        startAt = bundle.startAt,
        endAt = bundle.endAt,
        photoCount = bundle.photoCount,
        uploaderCount = bundle.uploaderCount,
        hasGroupPhoto = bundle.hasGroupPhoto,
        coverPhotoId = bundle.coverPhotoId,
        ruleTrace = traceOf(bundle),
        visibleFrom = visibility.visibleFrom(calculatedAt, bundle.dayKey),
        lastPhotoAddedAt = bundle.photos.mapNotNull { photoAddedAt[it.photoId] }.maxOrNull() ?: calculatedAt,
        createdAt = calculatedAt
    )

    // Writes what the calculation gives over a kept bundle, and says whether anything was different. The number, the
    // time it was made and the time it is shown from are not written (10-3).
    private fun write(entity: RecommendedBundle, bundle: RecoBundle, calculatedAt: Instant): Boolean {
        val trace = traceOf(bundle)
        val changed = entity.kind != bundle.kind ||
            entity.eventType != bundle.eventType ||
            entity.establishedBy != bundle.establishedBy ||
            entity.activity != bundle.activity ||
            entity.subIndex != bundle.subIndex ||
            entity.title != titleOf(bundle) ||
            entity.startAt != bundle.startAt ||
            entity.endAt != bundle.endAt ||
            entity.photoCount != bundle.photoCount ||
            entity.uploaderCount != bundle.uploaderCount ||
            entity.hasGroupPhoto != bundle.hasGroupPhoto ||
            entity.coverPhotoId != bundle.coverPhotoId ||
            canonical.writeValueAsString(entity.ruleTrace) != canonical.writeValueAsString(trace)
        if (!changed) return false

        entity.kind = bundle.kind
        entity.eventType = bundle.eventType
        entity.establishedBy = bundle.establishedBy
        entity.activity = bundle.activity
        entity.subIndex = bundle.subIndex
        entity.title = titleOf(bundle)
        entity.startAt = bundle.startAt
        entity.endAt = bundle.endAt
        entity.photoCount = bundle.photoCount
        entity.uploaderCount = bundle.uploaderCount
        entity.hasGroupPhoto = bundle.hasGroupPhoto
        entity.coverPhotoId = bundle.coverPhotoId
        entity.ruleTrace = trace
        entity.updatedAt = maxOf(calculatedAt, entity.createdAt)
        return true
    }

    private fun titleOf(bundle: RecoBundle): String =
        requireNotNull(bundle.title) { "a bundle of the calculation has a title (9)" }

    private fun traceOf(bundle: RecoBundle): Map<String, Any?> = mapper.convertValue(bundle.trace, traceType)

    // The photos of a bundle as what is kept of them: the role and the place on the card, by photo.
    private fun rowsOf(bundle: RecoBundle): Map<Long, Pair<String, Int?>> =
        bundle.photos.associate { it.photoId to (it.role.name to it.cardPosition) }

    private fun rowsOf(rows: List<RecommendedBundlePhoto>): Map<Long, Pair<String, Int?>> =
        rows.associate { it.photoId to (it.role.name to it.cardPosition) }

    private fun rowsFor(bundleId: Long, bundle: RecoBundle): List<RecommendedBundlePhoto> = bundle.photos.map {
        RecommendedBundlePhoto(
            bundleId = bundleId,
            photoId = it.photoId,
            role = it.role,
            cardPosition = it.cardPosition
        )
    }

    // A photo that is written now may still be in a bundle that was not touched, because its day is held. That row is
    // taken out so the photo can come to its new bundle, and the day of the old bundle is told to be calculated again.
    // A photo that is in a bundle that was touched is a mistake of the calculation and is not hidden.
    private fun takeOutOfHeldDays(rows: List<RecommendedBundlePhoto>, heldDays: Set<LocalDate>): Set<LocalDate> {
        if (rows.isEmpty()) return emptySet()
        val old = photos.findByPhotoIdIn(rows.map { it.photoId })
            .filter { existing -> rows.none { it === existing } }
        if (old.isEmpty()) return emptySet()

        val owners = bundles.findAllById(old.map { it.bundleId }.toSet()).associateBy { it.id }
        val stale = old.filter { owners.getValue(it.bundleId).dayKey in heldDays }
        check(stale.size == old.size) { "a photo is in a bundle that was kept, and in a bundle that is new (7-10)" }
        photos.deleteAllByIdInBatch(stale.map { it.id })
        return stale.map { owners.getValue(it.bundleId).dayKey }.toSet()
    }
}
