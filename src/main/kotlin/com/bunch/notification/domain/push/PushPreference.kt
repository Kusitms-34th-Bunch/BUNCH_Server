package com.bunch.notification.domain.push

import com.bunch.notification.domain.NotificationType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "push_preferences")
class PushPreference(
    @Id
    @Column(name = "member_id")
    val memberId: Long,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var categories: Map<String, Boolean> = emptyMap(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    companion object {
        // A member without settings receives every category. A type without a category is never pushed.
        fun allows(preference: PushPreference?, type: NotificationType): Boolean {
            if (preference?.enabled == false) return false
            if (type in PushCategory.ALWAYS) return true
            val category = PushCategory.of(type) ?: return false
            return preference?.categories?.get(category.name) != false
        }
    }
}
