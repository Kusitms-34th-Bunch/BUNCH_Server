package com.bunch.bundle.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

/** Latest placement of a live row, or the former neighbors/slot of a deleted row. */
@Entity
@Table(
    name = "bundle_photo_order_marks",
    indexes = [Index(name = "ix_bundle_photo_order_marks_bundle", columnList = "bundle_id")]
)
class BundlePhotoOrderMark(
    @Id
    @Column(name = "bundle_photo_id")
    val bundlePhotoId: Long,

    @Column(name = "bundle_id", nullable = false)
    val bundleId: Long,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "previous_id")
    var previousId: Long? = null,

    @Column(name = "next_id")
    var nextId: Long? = null,

    @Column(nullable = false)
    var slot: Int,

    @Column(name = "move_sequence")
    var moveSequence: Long? = null
)
