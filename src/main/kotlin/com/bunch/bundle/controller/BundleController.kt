package com.bunch.bundle.controller

import com.bunch.bundle.domain.BundleStatus
import com.bunch.bundle.dto.*
import com.bunch.bundle.service.BundleService
import com.bunch.global.common.ApiResponse
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.security.JwtSessionDetails
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/bundles")
class BundleController(private val bundles: BundleService) {
    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @RequestHeader("Idempotency-Key") key: UUID,
        @RequestBody request: CreateBundleRequest
    ) = ApiResponse.ok(bundles.create(actor(authentication), bunchId, key, request))

    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @RequestParam status: BundleStatus,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(bundles.list(userId(authentication), bunchId, status, page, size))

    @GetMapping("/summary")
    fun summary(authentication: Authentication, @PathVariable bunchId: Long) =
        ApiResponse.ok(bundles.summary(userId(authentication), bunchId))

    @GetMapping("/{id}")
    fun get(authentication: Authentication, @PathVariable bunchId: Long, @PathVariable id: Long) =
        ApiResponse.ok(bundles.get(userId(authentication), bunchId, id))

    @PutMapping("/{id}/title")
    fun title(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Bundle-Screen", defaultValue = "rest") screen: String,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleTextRequest
    ) = ApiResponse.ok(bundles.title(actor(authentication, screen), bunchId, id, request, correlation(requestId)))

    @PutMapping("/{id}/visibility")
    fun visibility(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleVisibilityRequest
    ) = ApiResponse.ok(bundles.visibility(actor(authentication), bunchId, id, request, correlation(requestId)))

    @PutMapping("/{id}/date")
    fun date(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Bundle-Screen", defaultValue = "rest") screen: String,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleDateRequest
    ) = ApiResponse.ok(bundles.date(actor(authentication, screen), bunchId, id, request, correlation(requestId)))

    @PutMapping("/{id}/comments/me")
    fun comment(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleTextRequest
    ) = ApiResponse.ok(bundles.comment(actor(authentication), bunchId, id, request, correlation(requestId)))

    @PostMapping("/{id}/participants")
    fun addParticipant(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleParticipantRequest
    ) = ApiResponse.ok(
        bundles.addParticipant(actor(authentication), bunchId, id, request.memberId, correlation(requestId))
    )

    @DeleteMapping("/{id}/participants/{memberId}")
    fun removeParticipant(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @PathVariable memberId: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?
    ) = ApiResponse.ok(bundles.removeParticipant(actor(authentication), bunchId, id, memberId, correlation(requestId)))

    @PostMapping("/{id}/leave")
    fun leave(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?
    ) = ApiResponse.ok(bundles.leave(actor(authentication), bunchId, id, correlation(requestId)))

    @PostMapping("/{id}/photos/changes")
    fun changePhotos(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Bundle-Screen", defaultValue = "rest") screen: String,
        @RequestHeader("Idempotency-Key") key: UUID,
        @RequestBody request: BundlePhotoChangesRequest
    ) = ApiResponse.ok(bundles.changePhotos(actor(authentication, screen), bunchId, id, key, request))

    @PostMapping("/{id}/photos/{photoId}/move")
    fun move(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @PathVariable photoId: Long,
        @RequestHeader("X-Bundle-Screen", defaultValue = "rest") screen: String,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: MoveBundlePhotoRequest
    ) = ApiResponse.ok(
        bundles.move(actor(authentication, screen), bunchId, id, photoId, request, correlation(requestId))
    )

    @PutMapping("/{id}/photos/{photoId}/caption")
    fun caption(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @PathVariable photoId: Long,
        @RequestHeader("X-Bundle-Screen", defaultValue = "rest") screen: String,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?,
        @RequestBody request: BundleTextRequest
    ) = ApiResponse.ok(
        bundles.caption(actor(authentication, screen), bunchId, id, photoId, request, correlation(requestId))
    )

    @PostMapping("/{id}/publish")
    fun publish(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("Idempotency-Key") key: UUID,
        @RequestBody request: PublishBundleRequest
    ) = ApiResponse.ok(bundles.publish(actor(authentication), bunchId, id, key, request))

    @DeleteMapping("/{id}")
    fun delete(
        authentication: Authentication,
        @PathVariable bunchId: Long,
        @PathVariable id: Long,
        @RequestHeader("X-Request-Id", required = false) requestId: UUID?
    ): ApiResponse<Unit> {
        bundles.delete(actor(authentication), bunchId, id, correlation(requestId))
        return ApiResponse.empty(Unit)
    }

    private fun userId(authentication: Authentication) = authentication.principal.toString().toLong()

    private fun actor(authentication: Authentication, screen: String = "rest"): BundleActor {
        if (screen.isBlank() || screen.length > 100) throw BusinessException(ErrorCode.INVALID_INPUT)
        val session = (authentication.details as? JwtSessionDetails)?.sessionId
            ?: throw BusinessException(ErrorCode.UNAUTHORIZED)
        return BundleActor(userId(authentication), session, screen)
    }

    private fun correlation(value: UUID?) = (value ?: UUID.randomUUID()).toString()
}
