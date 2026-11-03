package com.bunch.notification.controller

import com.bunch.global.common.ApiResponse
import com.bunch.notification.service.NotificationService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bunches/{bunchId}/notifications")
class NotificationController(private val notifications: NotificationService) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.ok(notifications.list(userId.toLong(), bunchId, page, size))

    @PutMapping("/{notificationId}/read")
    fun read(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @PathVariable notificationId: Long
    ) = ApiResponse.ok(notifications.read(userId.toLong(), bunchId, notificationId))

    @PostMapping("/read-all")
    fun readAll(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long
    ) = ApiResponse.ok(notifications.readAll(userId.toLong(), bunchId))

    @PostMapping("/{notificationId}/open")
    fun open(
        @AuthenticationPrincipal userId: String,
        @PathVariable bunchId: Long,
        @PathVariable notificationId: Long
    ) = ApiResponse.ok(notifications.open(userId.toLong(), bunchId, notificationId))
}
