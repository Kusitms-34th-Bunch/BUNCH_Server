package com.bunch.feed.domain

import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundlePhoto
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface FeedCardRepository : Repository<Bundle, Long> {
    @Query("""select photo from BundlePhoto photo join fetch photo.imageAsset
        where photo.bundleId in :bundleIds order by photo.bundleId, photo.position, photo.id
    """)
    fun photos(bundleIds: Collection<Long>): List<BundlePhoto>

    @Query("""select bunch.id as id, bunch.name as name, image.url as imageUrl,
        bunch.characterType as characterType, bunch.visibility as visibility
        from Bunch bunch left join bunch.profileImageAsset image where bunch.id in :bunchIds
    """)
    fun bunches(bunchIds: Collection<Long>): List<FeedBunchProfile>

    interface FeedBunchProfile {
        val id: Long
        val name: String
        val imageUrl: String?
        val characterType: com.bunch.bunch.domain.CharacterType
        val visibility: com.bunch.bunch.domain.Visibility
    }
}
