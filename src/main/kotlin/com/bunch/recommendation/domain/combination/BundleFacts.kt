package com.bunch.recommendation.domain.combination

/**
 * What the event bundles and the general bundles have in common when they are made into a [RecoBundle] (recommended
 * bundle specification 3-1, 4-5, 11): the number of pictures and of members counted as the specification counts them,
 * whether a usable photo shows 3 people or more, the time of the first and of the last photo, and the part of the
 * trace that every bundle has. What only one kind has (the event, the general part) is given by the one who makes it.
 */
class BundleFacts(
    private val settings: CombinationSettings = CombinationSettings(),
    private val eligibility: BundleEligibility = BundleEligibility(settings)
) {
    /**
     * The bundle of [photos] of [day]. [roleOf] tells why each photo is in it. The bundle lasts from its first photo to
     * its last (4-5), or [span] when the photos have no time to tell it.
     */
    fun build(
        day: DayPreparation.Ready,
        kind: BundleKind,
        photos: List<PreparedPhoto>,
        roleOf: (PreparedPhoto) -> PhotoRole,
        eventType: EventType? = null,
        establishedBy: EstablishedBy? = null,
        activity: ActivityCode? = null,
        subIndex: Int? = null,
        span: TimeRange? = null,
        event: RuleTrace.Event? = null,
        general: RuleTrace.General? = null
    ): RecoBundle {
        val counts = counts(photos, day)
        val times = photos.mapNotNull { it.instant }
        val range = span ?: TimeRange(times.min(), times.max())
        return RecoBundle(
            dayKey = day.dayKey,
            kind = kind,
            eventType = eventType,
            establishedBy = establishedBy,
            activity = activity,
            subIndex = subIndex,
            photos = photos.map { RecoBundle.BundlePhoto(it.photoId, roleOf(it)) },
            coverPhotoId = null,
            startAt = range.start,
            endAt = range.end,
            photoCount = counts.photos,
            uploaderCount = counts.uploaders,
            hasGroupPhoto = hasGroupPhoto(photos),
            trace = RuleTrace(
                dayKey = day.dayKey,
                kind = kind,
                counts = counts,
                configVersion = settings.configVersion,
                event = event,
                general = general
            )
        )
    }

    /**
     * The bundle with [added] photos put to it, with the numbers counted again (7-9: the photos with a date only are
     * counted in the bundle, but whether it stands was decided before they came). The time of the bundle stays.
     */
    fun add(
        bundle: RecoBundle,
        day: DayPreparation.Ready,
        added: List<PreparedPhoto>,
        role: PhotoRole
    ): RecoBundle {
        val byId = day.photos.associateBy { it.photoId }
        val all = (bundle.photos.map { it.photoId } + added.map { it.photoId }).mapNotNull { byId[it] }
        val counts = counts(all, day)
        return bundle.copy(
            photos = bundle.photos + added.map { RecoBundle.BundlePhoto(it.photoId, role) },
            photoCount = counts.photos,
            uploaderCount = counts.uploaders,
            hasGroupPhoto = hasGroupPhoto(all),
            trace = bundle.trace.copy(counts = counts)
        )
    }

    private fun counts(photos: List<PreparedPhoto>, day: DayPreparation.Ready) = RuleTrace.Counts(
        photos = eligibility.photoCount(photos, day.duplicateGroups),
        uploaders = eligibility.uploaderCount(photos, day.duplicateGroups)
    )

    private fun hasGroupPhoto(photos: List<PreparedPhoto>): Boolean =
        photos.any { eligibility.isUsable(it) && it.photo.analysis.people == PeopleCount.P3_PLUS }
}
