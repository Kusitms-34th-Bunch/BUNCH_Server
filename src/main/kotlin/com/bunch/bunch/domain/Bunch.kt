package com.bunch.bunch.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "bunches", uniqueConstraints = [UniqueConstraint(
    name = "uk_bunch_creation_request", columnNames = ["created_by", "creation_request_key"]
)])
class Bunch(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(nullable = false, length = 120, unique = true) val name: String,
    @Column(nullable = false, columnDefinition = "text") val introduction: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val visibility: Visibility,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val characterType: CharacterType,
    @Column(length = 255) var imagePublicId: String? = null,
    @Column(columnDefinition = "text") var imageUrl: String? = null,
    @Column(nullable = false) val createdBy: Long,
    @Column(nullable = false) val creationRequestKey: UUID,
    @Column(nullable = false, length = 64) val creationFingerprint: String,
    @Column(nullable = false) val createdAt: Instant
)
