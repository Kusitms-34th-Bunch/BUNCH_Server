package com.bunch.photo.domain

import org.springframework.data.jpa.repository.JpaRepository

interface PhotoImageFingerprintRepository : JpaRepository<PhotoImageFingerprint, Long>
