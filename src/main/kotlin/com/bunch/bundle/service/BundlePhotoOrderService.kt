package com.bunch.bundle.service

import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundlePhoto
import com.bunch.bundle.domain.BundlePhotoOrderMark
import com.bunch.bundle.domain.BundlePhotoOrderMarkRepository
import com.bunch.bundle.domain.BundlePhotoRepository
import com.bunch.bundle.domain.BundleRequest
import com.bunch.bundle.domain.BundleRequestRepository
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID

@Service
class BundlePhotoOrderService(
    private val photos: BundlePhotoRepository,
    private val marks: BundlePhotoOrderMarkRepository,
    private val requests: BundleRequestRepository
) {
    /** Caller owns bunch -> bundle locks, authorization, status, revision, activity and events. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun moveLocked(
        bundle: Bundle,
        memberId: Long,
        photoId: Long,
        previousId: Long?,
        nextId: Long?,
        requestId: String
    ): Boolean {
        if (requestId.isBlank()) fail(ErrorCode.INVALID_INPUT)
        val key = UUID.nameUUIDFromBytes("$OPERATION:$requestId".toByteArray(UTF_8))
        val fingerprint = fingerprint(bundle.id, photoId, previousId, nextId)
        requests.findByBunchIdAndMemberIdAndOperationAndRequestKey(bundle.bunchId, memberId, OPERATION, key)
            ?.let {
                if (it.fingerprint != fingerprint) fail(ErrorCode.IDEMPOTENCY_CONFLICT)
                return false
            }
        val current = photos.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id)
        val metadata = marks.findAllByBundleId(bundle.id).associateBy { it.bundlePhotoId }
        val target = current.firstOrNull { it.id == photoId }
        if (target == null) {
            if (metadata[photoId]?.deleted != true) fail(ErrorCode.RESOURCE_NOT_FOUND)
            remember(bundle, memberId, key, fingerprint)
            return false
        }
        if (photoId == previousId || photoId == nextId || (previousId != null && previousId == nextId)) {
            fail(ErrorCode.BUNDLE_PHOTO_CONFLICT)
        }
        val rest = current.filterNot { it.id == photoId }.toMutableList()
        val indices = rest.mapIndexed { index, row -> row.id to index }.toMap()
        listOfNotNull(previousId, nextId).forEach { anchor ->
            if (anchor !in indices && metadata[anchor]?.deleted != true) fail(ErrorCode.BUNDLE_PHOTO_CONFLICT)
        }
        if (previousId == null && nextId == null && rest.isNotEmpty()) fail(ErrorCode.BUNDLE_PHOTO_CONFLICT)

        val nextBoundary = nextId?.let { boundary(it, true, indices, metadata, rest.size) }
        val previousBoundary = previousId?.let { boundary(it, false, indices, metadata, rest.size) }
        var insertion = nextBoundary ?: previousBoundary ?: 0
        // A previous-only gap has no right boundary. Append after earlier distinct moves to
        // that same gap, rather than inserting each newcomer directly after the anchor.
        if (nextId == null) {
            val lastPeer = rest.indexOfLast { row ->
                metadata[row.id]?.let {
                    !it.deleted && it.moveSequence != null && it.previousId == previousId && it.nextId == nextId
                } == true
            }
            insertion = maxOf(insertion, lastPeer + 1)
        }
        rest.add(insertion.coerceIn(0, rest.size), target)
        val changed = rest.map { it.id } != current.map { it.id }
        rest.forEachIndexed { index, row -> row.position = index }
        val receipt = remember(bundle, memberId, key, fingerprint)
        val mark = metadata[photoId] ?: BundlePhotoOrderMark(photoId, bundle.id, slot = target.position)
        mark.previousId = previousId
        mark.nextId = nextId
        mark.slot = target.position
        mark.moveSequence = receipt.id
        marks.save(mark)
        return changed
    }

    /** Capture all neighbors before deleting any row, including batch removals. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun beforeRemoveLocked(bundle: Bundle, rows: Collection<BundlePhoto>) {
        if (rows.isEmpty()) return
        if (rows.any { it.bundleId != bundle.id }) fail(ErrorCode.BUNDLE_PHOTO_CONFLICT)
        val current = photos.findAllByBundleIdOrderByPositionAscIdAsc(bundle.id)
        val metadata = marks.findAllByBundleId(bundle.id).associateBy { it.bundlePhotoId }
        val removedIds = rows.map { it.id }.toSet()
        current.forEachIndexed { index, row ->
            if (row.id in removedIds) {
                val mark = metadata[row.id] ?: BundlePhotoOrderMark(row.id, bundle.id, slot = index)
                mark.deleted = true
                mark.previousId = current.getOrNull(index - 1)?.id
                mark.nextId = current.getOrNull(index + 1)?.id
                mark.slot = index
                marks.save(mark)
            }
        }
    }

    /** A picker reselection supersedes the live row's old drag gap, not deletion history. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun clearReselectedLocked(bundle: Bundle, rows: Collection<BundlePhoto>) {
        if (rows.isEmpty()) return
        if (rows.any { it.bundleId != bundle.id }) fail(ErrorCode.BUNDLE_PHOTO_CONFLICT)
        val current = marks.findAllById(rows.map { it.id })
            .filter { it.bundleId == bundle.id && !it.deleted }
        marks.deleteAll(current)
    }

    private fun boundary(
        anchor: Long,
        before: Boolean,
        indices: Map<Long, Int>,
        metadata: Map<Long, BundlePhotoOrderMark>,
        size: Int
    ): Int {
        indices[anchor]?.let { return if (before) it else it + 1 }
        val tombstone = metadata.getValue(anchor)
        // Follow only deleted neighbors, never another live photo's last move metadata.
        fun surviving(start: Long?, forward: Boolean): Int? {
            val visited = mutableSetOf(anchor)
            var id = start
            while (id != null && visited.add(id)) {
                indices[id]?.let { return if (forward) it else it + 1 }
                val mark = metadata[id]?.takeIf { it.deleted } ?: return null
                id = if (forward) mark.nextId else mark.previousId
            }
            return null
        }
        return surviving(tombstone.nextId, true)
            ?: surviving(tombstone.previousId, false)
            ?: tombstone.slot.coerceIn(0, size)
    }

    private fun remember(bundle: Bundle, memberId: Long, key: UUID, fingerprint: String): BundleRequest =
        requests.save(BundleRequest(
            bunchId = bundle.bunchId,
            memberId = memberId,
            operation = OPERATION,
            requestKey = key,
            fingerprint = fingerprint,
            bundleId = bundle.id,
            revision = bundle.revision
        ))

    private fun fingerprint(bundleId: Long, photoId: Long, previousId: Long?, nextId: Long?): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$bundleId:$photoId:$previousId:$nextId".toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun fail(code: ErrorCode): Nothing = throw BusinessException(code)

    private companion object {
        const val OPERATION = "PHOTO_MOVE"
    }
}
