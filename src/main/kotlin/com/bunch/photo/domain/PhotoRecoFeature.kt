package com.bunch.photo.domain

import com.bunch.global.image.ImageQualityGrade
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * The features of a photo that the recommendation uses (recommended bundle specification 4-5). It is kept apart from
 * the photo, like the image fingerprint, and the database deletes it with the photo. A photo without a row, or with
 * no [source], has no known original type. A photo without a [quality] was uploaded before the quality could be
 * calculated, or its image could not be read. A photo without an [aiStatus] was never sent to the analysis.
 *
 * The model, the answer and the time of the analysis are columns of the table too. They are written and read with their
 * own statements ([PhotoRecoFeatureRepository]), because the answer is JSON that is kept as the text the model gave, so
 * the entity does not hold them.
 */
@Entity
@Table(name = "photo_reco_feature")
class PhotoRecoFeature(
    @Id
    @Column(name = "photo_id")
    val photoId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16)
    val source: PhotoSourceType? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status", length = 16)
    val aiStatus: PhotoAiStatus? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "quality", length = 8)
    val quality: ImageQualityGrade? = null
)
