package com.bunch.recommendation.service

import com.bunch.bunch.domain.BunchMembershipRepository
import com.bunch.bunch.domain.BunchRepository
import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.bundle.domain.BundleEventType
import com.bunch.bundle.dto.BundleChangedEvent
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImageUrls
import com.bunch.photo.service.PhotoChangedEvent
import com.bunch.recommendation.domain.BundleCandidate
import com.bunch.recommendation.domain.CandidateExpiry
import com.bunch.recommendation.domain.CandidateMatch
import com.bunch.recommendation.domain.CandidateMatcher
import com.bunch.recommendation.domain.CandidatePhoto
import com.bunch.recommendation.domain.CardSelection
import com.bunch.recommendation.domain.CardSelector
import com.bunch.recommendation.domain.MaterialPhotoRow
import com.bunch.recommendation.domain.PhotoCluster
import com.bunch.recommendation.domain.PhotoClusterer
import com.bunch.recommendation.domain.PhotoSample
import com.bunch.recommendation.domain.RecommendationRepository
import com.bunch.recommendation.domain.TitleContextBuilder
import com.bunch.recommendation.domain.TitleGenerator
import com.bunch.recommendation.domain.UsedCandidate
import com.bunch.recommendation.dto.RecommendedBundleCard
import com.bunch.recommendation.dto.RecommendedBundleDetailResponse
import com.bunch.recommendation.dto.RecommendedBundleListResponse
import com.bunch.recommendation.dto.RecommendedBundlePhoto
import com.bunch.recommendation.dto.RecommendedUploader
import jakarta.persistence.EntityManager
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

@Service
class RecommendationService(
    private val recommendations: RecommendationRepository,
    private val entityManager: EntityManager,
    private val bunches: BunchRepository,
    private val memberships: BunchMembershipRepository,
    private val clusterer: PhotoClusterer,
    private val cardSelector: CardSelector,
    private val matcher: CandidateMatcher,
    private val expiry: CandidateExpiry,
    private val titleGenerator: TitleGenerator,
    private val titleContexts: TitleContextBuilder,
    private val accessNotices: BunchAccessNoticeService
) {
    /** The photos of a bunch a recommended bundle can be made of, one sample per photo. */
    @Transactional(readOnly = true)
    fun materialPhotos(bunchId: Long): List<PhotoSample> = toSamples(recommendations.findMaterialRows(bunchId))

    /**
     * The cards of the recommended bundle sheet of a bunch (4.2.1, 4.2.2). The list belongs to the bunch, so every
     * member gets the same one. A bundle with no photo of its card left is left out: its photos were all deleted and
     * the next calculation will delete the bundle. The count is the photos that are left, so it can be under six until
     * then.
     */
    @Transactional(readOnly = true)
    fun bundleCards(userId: Long, bunchId: Long): RecommendedBundleListResponse {
        accessNotices.requireMemberOrAccessNotice(userId, bunchId)
        val candidates = recommendations.findOrderedCandidates(bunchId)
        if (candidates.isEmpty()) return RecommendedBundleListResponse(emptyList())

        val photosOf = recommendations.findCardPhotoRows(candidates.map { it.id }).groupBy { it.candidateId }
        val uploadersOf = uploadersOf(photosOf.values.flatten().map { it.photoId })
        val cards = candidates.mapNotNull { candidate ->
            val photos = photosOf[candidate.id] ?: return@mapNotNull null
            RecommendedBundleCard(
                id = candidate.id,
                title = candidate.title,
                startDate = candidate.startDate,
                endDate = candidate.endDate,
                photoCount = photos.size,
                uploaders = chipsOf(photos.map { uploadersOf[it.photoId].orEmpty() }),
                coverImageUrl = PhotoImageUrls.full(photos.first().imageUrl)
            )
        }
        return RecommendedBundleListResponse(cards)
    }

    /**
     * The photos of a recommended bundle in the order they are applied in (4.2.3, 4.2.5). The photos that were deleted
     * are not there, so the app gets only the photos that can still be used. A bundle that was deleted, or one of
     * another bunch, is not found, and the app tells the member and reloads the sheet (4.2.5).
     */
    @Transactional(readOnly = true)
    fun bundlePhotos(userId: Long, bunchId: Long, candidateId: Long): RecommendedBundleDetailResponse {
        accessNotices.requireMemberOrAccessNotice(userId, bunchId)
        val candidate = recommendations.findCandidate(bunchId, candidateId)
            ?: throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)
        val rows = recommendations.findCardPhotoRows(listOf(candidateId))
        if (rows.isEmpty()) throw BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

        val uploadersOf = uploadersOf(rows.map { it.photoId })
        val photos = rows.map { row ->
            RecommendedBundlePhoto(
                photoId = row.photoId,
                thumbnailUrl = PhotoImageUrls.thumbnail(row.imageUrl),
                imageUrl = PhotoImageUrls.full(row.imageUrl),
                capturedDate = row.capturedDate,
                capturedTime = row.capturedTime,
                uploaders = uploadersOf[row.photoId].orEmpty()
            )
        }
        return RecommendedBundleDetailResponse(candidate.id, candidate.title, photos)
    }

    /**
     * Notes the date of a photo change in the transaction of the change itself, so the note is there exactly when the
     * change is (4.2.9 rule 1). A photo without a date is part of no recommended bundle (4.2.7), so it is not noted.
     * Whether a photo was added does not matter here: the time of the last photo added comes from the photos.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun noteChange(event: PhotoChangedEvent) {
        val date = event.capturedDate ?: return
        recommendations.markChanged(event.bunchId, date, Instant.now())
    }

    /**
     * Notes that the photos of a draft changed, so the recommended bundles are calculated again (4.1.3 rule 6, 4.2.9).
     * A draft taking photos, giving photos back or being deleted changes what is left to recommend. Only these three
     * changes of a bundle are listened to, and they are always made in a transaction, so the note is there exactly
     * when the change is. The bundle events of the live editing are left out by the condition.
     * A bundle that is deleted gives its photos back (4.2.9 rule 1): they are not noted as used any more, so they can
     * be recommended again. This runs before the bundle and its photos are deleted.
     */
    @EventListener(condition = "#event.type.name() matches 'CREATED|PHOTOS|DELETED'")
    @Transactional(propagation = Propagation.MANDATORY)
    fun noteBundleChange(event: BundleChangedEvent) {
        if (event.type == BundleEventType.DELETED) recommendations.releaseUsedPhotosOfBundle(event.bundleId)
        recommendations.markAllChanged(event.bunchId, Instant.now())
    }

    /**
     * Takes the recommended bundle a draft is made from (4.1.3 rules 5 and 8). The recommended bundle is deleted and
     * its photos are noted as used, so the photos the draft does not take only become a new recommended bundle when a
     * photo is added to them (4.2.9 rule 5). The draft gets its title and its first day. Returns null when the
     * recommended bundle is not there any more or belongs to another bunch; the draft is made without it then. The
     * caller is the creation of the draft, which has locked the bunch, so two drafts never take the same recommended
     * bundle.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun useCandidate(bunchId: Long, candidateId: Long): UsedCandidate? {
        val candidate = recommendations.findCandidate(bunchId, candidateId) ?: return null
        recommendations.markCandidatePhotosUsed(bunchId, candidate.id)
        recommendations.deleteCandidates(listOf(candidate.id))
        return UsedCandidate(candidate.title, candidate.startDate)
    }

    /**
     * The recommended bundles of a bunch in the order of the list (4.2.9 rule 6). The list belongs to the bunch, so
     * every member of it gets the same one.
     */
    @Transactional(readOnly = true)
    fun orderedCandidates(bunchId: Long): List<BundleCandidate> = recommendations.findOrderedCandidates(bunchId)

    /**
     * Deletes the recommended bundles whose last photo was added more than 30 days ago at [now] and returns how many
     * (4.2.9 rule 4). The photos stay in the photo box. Running it again deletes nothing more, so a run that was missed
     * or repeated does no harm.
     */
    @Transactional
    fun expireCandidates(now: Instant): Int = recommendations.deleteExpiredCandidates(expiry.cutoff(now))

    /** The bunches whose photos have not changed since [cutoff], the ones waiting the longest first. */
    @Transactional(readOnly = true)
    fun quietBunchIds(cutoff: Instant, limit: Int): List<Long> = recommendations.findQuietBunchIds(cutoff, limit)

    /**
     * Calculates the noted dates of a bunch once when its photos did not change since [cutoff] (4.2.9 rule 2). The
     * bunch is locked first and the check is made again, because a change that was committed while this waited for the
     * lock is newer than the cutoff and has to wait for its own five quiet minutes.
     */
    @Transactional
    fun recalculateIfQuiet(bunchId: Long, cutoff: Instant) {
        if (bunches.findLocked(bunchId) == null) return

        val changed = recommendations.findChangedDates(bunchId)
        if (changed.isEmpty() || changed.any { it.changedAt.isAfter(cutoff) }) return

        recalculate(bunchId, changed.map { it.capturedDate }.toSet())
        recommendations.deleteChangedDates(bunchId)
    }

    /**
     * Calculates the recommended bundles of a bunch again after its photos changed on [changedDates] (4.2.9). Only the
     * candidates and clusters on those dates and the day before and after are changed, because a cluster over midnight
     * can reach into the next day. The clusters are made out of all the material of the bunch (at most 1,000 photos),
     * so a cluster that reaches out of those dates is still seen whole.
     * The bunch is locked like the photo changes are, so two calculations of a bunch never run at the same time and
     * the photos do not change while they are read.
     */
    @Transactional
    fun recalculate(bunchId: Long, changedDates: Set<LocalDate>) {
        if (changedDates.isEmpty()) return
        if (bunches.findLocked(bunchId) == null) return

        val affected = changedDates.flatMap { listOf(it.minusDays(1), it, it.plusDays(1)) }.toSet()
        val samples = materialPhotos(bunchId)
        val clusters = clusterer.cluster(samples).filter { touches(it.startDate, it.endDate, affected) }
        val sampleById = samples.associateBy { it.photoId }
        val selections = clusters.associateWith { cluster ->
            cardSelector.select(cluster.photoIds.map { sampleById.getValue(it) })
        }

        val stored = recommendations.findCandidates(bunchId)
        val inRange = stored.filter { touches(it.startDate, it.endDate, affected) }
        val match = matcher.match(photoIdsOf(inRange), clusters) { selections.getValue(it).recommended }
        val addedAt = addedAtOf(clusters)

        if (match.deletedCandidateIds.isNotEmpty()) recommendations.deleteCandidates(match.deletedCandidateIds)
        keep(match, inRange, selections, addedAt)
        val toCreate = match.copy(created = withNewPhotos(notExpired(match.created, addedAt), bunchId))
        create(bunchId, toCreate, stored, selections, sampleById, addedAt)
    }

    // A cluster whose last photo was added more than 30 days ago is not made a candidate. It would be deleted at the
    // next daily check, and a bundle that expired must not come back until a new photo is added (4.2.9 rule 4). The old
    // photos are still material, so a new photo gives the cluster a recent time and it comes back as a new candidate.
    // The photos of a recommended bundle a draft was made from are not recommended again (4.1.3 rule 5). A cluster of
    // only those photos is not a new recommended bundle; one with a photo that was added since is (4.2.9 rule 5).
    private fun withNewPhotos(clusters: List<PhotoCluster>, bunchId: Long): List<PhotoCluster> {
        if (clusters.isEmpty()) return clusters

        val used = recommendations.findUsedPhotoIds(bunchId).toSet()
        return clusters.filter { cluster -> cluster.photoIds.any { it !in used } }
    }

    private fun notExpired(clusters: List<PhotoCluster>, addedAt: Map<Long, Instant>): List<PhotoCluster> {
        val now = Instant.now()

        return clusters.filter { !expiry.isExpired(latestAdded(it, addedAt), now) }
    }

    private fun uploadersOf(photoIds: List<Long>): Map<Long, List<RecommendedUploader>> =
        recommendations.findUploaderProfiles(photoIds.distinct())
            .groupBy({ it.photoId }, { RecommendedUploader(it.memberId, it.nickname, it.imageUrl) })

    // The members of a card, the one who uploaded the most photos of it first and the lower member number first when
    // they uploaded as many. A photo several members uploaded counts for each of them. The specification does not say
    // in which order the chips go; this is the order the nicknames of a title are put in.
    private fun chipsOf(uploadersOfPhotos: List<List<RecommendedUploader>>): List<RecommendedUploader> {
        val uploaders = uploadersOfPhotos.flatten()
        val photoCounts = uploaders.groupingBy { it.memberId }.eachCount()
        val mostPhotosFirst = compareByDescending<RecommendedUploader> { photoCounts.getValue(it.memberId) }

        return uploaders.distinctBy { it.memberId }.sortedWith(mostPhotosFirst.thenBy { it.memberId })
    }

    private fun toSamples(rows: List<MaterialPhotoRow>): List<PhotoSample> =
        rows.groupBy { it.photoId }.map { (photoId, uploads) ->
            PhotoSample(
                photoId = photoId,
                capturedDate = uploads.first().capturedDate,
                capturedTime = uploads.first().capturedTime,
                uploaderIds = uploads.map { it.memberId }.toSet(),
                batchIds = uploads.map { it.uploadBatchId }.toSet() + listOfNotNull(uploads.first().dateBatchId),
                fingerprint = uploads.first().imageFingerprint
            )
        }

    // A candidate or a cluster is on the dates from its first day to its last day.
    private fun touches(startDate: LocalDate, endDate: LocalDate?, dates: Set<LocalDate>): Boolean =
        dates.any { !it.isBefore(startDate) && !it.isAfter(endDate ?: startDate) }

    private fun photoIdsOf(stored: List<BundleCandidate>): Map<Long, Set<Long>> {
        if (stored.isEmpty()) return emptyMap()

        val photos = recommendations.findCandidatePhotos(stored.map { it.id })
            .groupBy({ it.candidateId }, { it.photoId })

        return stored.associate { it.id to photos[it.id].orEmpty().toSet() }
    }

    private fun addedAtOf(clusters: List<PhotoCluster>): Map<Long, Instant> =
        recommendations.findAddedAt(clusters.flatMap { it.photoIds }.toSet())
            .associate { it.photoId to it.createdAt }

    // The title stays. The time of the last photo added only goes up, so deleting photos never moves it back (4.2.9
    // rule 3), and a photo that was moved to this cluster or came back from a draft is not a new photo.
    private fun keep(
        match: CandidateMatch,
        inRange: List<BundleCandidate>,
        selections: Map<PhotoCluster, CardSelection>,
        addedAt: Map<Long, Instant>
    ) {
        if (match.kept.isEmpty()) return

        val byId = inRange.associateBy { it.id }
        recommendations.deleteCandidatePhotos(match.kept.keys)
        match.kept.forEach { (candidateId, cluster) ->
            val candidate = byId.getValue(candidateId)
            candidate.startDate = cluster.startDate
            candidate.endDate = cluster.endDate
            candidate.lastPhotoAddedAt = maxOf(candidate.lastPhotoAddedAt, latestAdded(cluster, addedAt))
            candidate.updatedAt = Instant.now()
            photosOf(candidateId, cluster, selections.getValue(cluster)).forEach(entityManager::persist)
        }
    }

    // The new candidates one after the other, so a title made for one is not made again for the next (4.2.2).
    private fun create(
        bunchId: Long,
        match: CandidateMatch,
        stored: List<BundleCandidate>,
        selections: Map<PhotoCluster, CardSelection>,
        sampleById: Map<Long, PhotoSample>,
        addedAt: Map<Long, Instant>
    ) {
        if (match.created.isEmpty()) return

        val datedPhotos = toSamples(recommendations.findDatedRows(bunchId))
        val nicknames = nicknamesOf(match.created, sampleById)
        val memberCount = memberships.countByBunchIdAndStatus(bunchId).toInt()
        val shownTitles = stored.filter { it.id !in match.deletedCandidateIds }.map { it.title }.toMutableSet()
        val recentSentenceIds = stored
            .sortedWith(compareByDescending<BundleCandidate> { it.createdAt }.thenByDescending { it.id })
            .mapNotNull { it.titleSentenceKey }
            .toMutableList()

        match.created.forEach { cluster ->
            val context = titleContexts.build(
                cluster = cluster,
                nicknames = nicknames,
                bunchMemberCount = memberCount,
                datedPhotos = datedPhotos,
                visibleTitles = shownTitles.toSet(),
                recentSentenceIds = recentSentenceIds.toList()
            )
            val title = titleGenerator.generate(context)
            val now = Instant.now()
            val candidate = BundleCandidate(
                bunchId = bunchId,
                startDate = cluster.startDate,
                endDate = cluster.endDate,
                title = title.title,
                titleSentenceKey = title.sentenceId,
                lastPhotoAddedAt = latestAdded(cluster, addedAt),
                createdAt = now,
                updatedAt = now
            )
            entityManager.persist(candidate)
            photosOf(candidate.id, cluster, selections.getValue(cluster)).forEach(entityManager::persist)

            shownTitles += title.title
            title.sentenceId?.let { recentSentenceIds.add(0, it) }
        }
    }

    private fun nicknamesOf(clusters: List<PhotoCluster>, sampleById: Map<Long, PhotoSample>): Map<Long, String> {
        val memberIds = clusters.flatMap { it.photoIds }.flatMap { sampleById.getValue(it).uploaderIds }.toSet()

        return recommendations.findNicknames(memberIds)
            .mapNotNull { row -> row.nickname?.let { row.memberId to it } }
            .toMap()
    }

    private fun latestAdded(cluster: PhotoCluster, addedAt: Map<Long, Instant>): Instant =
        cluster.photoIds.maxOf { addedAt.getValue(it) }

    // Every photo of the cluster is kept. Only the photos of the card have a position, in the order they are shown.
    private fun photosOf(candidateId: Long, cluster: PhotoCluster, selection: CardSelection): List<CandidatePhoto> {
        val positions = selection.photoIds.withIndex().associate { (index, photoId) -> photoId to index + 1 }

        return cluster.photoIds.map { photoId ->
            CandidatePhoto(candidateId = candidateId, photoId = photoId, position = positions[photoId])
        }
    }
}
