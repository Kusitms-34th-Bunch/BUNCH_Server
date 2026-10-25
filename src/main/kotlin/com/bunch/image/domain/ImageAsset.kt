package com.bunch.image.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "image_assets")
class ImageAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 255)
    val publicId: String,

    @Column(nullable = false, columnDefinition = "text")
    val url: String,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
