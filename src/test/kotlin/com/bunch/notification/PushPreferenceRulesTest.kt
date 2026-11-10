package com.bunch.notification

import com.bunch.notification.domain.NotificationType
import com.bunch.notification.domain.push.PushCategory
import com.bunch.notification.domain.push.PushPreference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class PushPreferenceRulesTest {
    @Test
    fun `categories group the notifications as the my page specification does`() {
        assertThat(PushCategory.BUNDLE_NEWS.types).containsExactlyInAnyOrder(
            NotificationType.DRAFT_PARTICIPANT_ADDED,
            NotificationType.DRAFT_ACTIVITY,
            NotificationType.COMMENT_REMINDER,
            NotificationType.DRAFT_PARTICIPANT_REMOVED,
            NotificationType.DRAFT_READY_TO_PUBLISH,
            NotificationType.DRAFT_PARTICIPANTS_INSUFFICIENT,
            NotificationType.BUNDLE_PUBLISHED,
            NotificationType.CANDIDATE_CREATED
        )
        assertThat(PushCategory.MEMBER_ROLE.types).containsExactlyInAnyOrder(
            NotificationType.MEMBER_JOINED,
            NotificationType.CO_OWNER_GRANTED,
            NotificationType.CO_OWNER_REVOKED,
            NotificationType.OWNER_TRANSFERRED,
            NotificationType.BUNCH_VISIBILITY_CHANGED
        )
        assertThat(PushCategory.FOLLOW.types).containsExactlyInAnyOrder(
            NotificationType.BUNCH_FOLLOWED,
            NotificationType.FOLLOW_REQUESTED,
            NotificationType.FOLLOW_REQUEST_APPROVED
        )
        assertThat(PushCategory.REACTION.types).containsExactlyInAnyOrder(
            NotificationType.SOCIAL_COMMENT_CREATED,
            NotificationType.BUNDLE_LIKED
        )
        assertThat(PushCategory.ALWAYS).containsExactlyInAnyOrder(
            NotificationType.MEMBER_REMOVED,
            NotificationType.BUNCH_DELETED
        )
    }

    @Test
    fun `every notification type is in one category, always sent, or the removed growth notice`() {
        val grouped = PushCategory.entries.flatMap { it.types }

        assertThat(grouped).doesNotHaveDuplicates()
        assertThat(PushCategory.ALWAYS).doesNotContainAnyElementsOf(grouped)
        assertThat(NotificationType.entries - grouped.toSet() - PushCategory.ALWAYS)
            .containsExactly(NotificationType.CHARACTER_GROWN)
    }

    @Test
    fun `a member without settings receives every category and always sent types`() {
        (PushCategory.entries.flatMap { it.types } + PushCategory.ALWAYS).forEach {
            assertThat(PushPreference.allows(null, it)).describedAs(it.name).isTrue()
        }
    }

    @Test
    fun `a type without a category is never pushed`() {
        assertThat(PushPreference.allows(null, NotificationType.CHARACTER_GROWN)).isFalse()
        assertThat(PushPreference.allows(preference(), NotificationType.CHARACTER_GROWN)).isFalse()
    }

    @Test
    fun `turning the app push off stops every type including the always sent ones`() {
        val off = preference(enabled = false)

        NotificationType.entries.forEach {
            assertThat(PushPreference.allows(off, it)).describedAs(it.name).isFalse()
        }
    }

    @ParameterizedTest
    @EnumSource(PushCategory::class)
    fun `switching one category off stops only its types`(category: PushCategory) {
        val settings = preference(categories = mapOf(category.name to false))

        PushCategory.entries.forEach { other ->
            other.types.forEach {
                assertThat(PushPreference.allows(settings, it))
                    .describedAs("${other.name}/${it.name}")
                    .isEqualTo(other != category)
            }
        }
        PushCategory.ALWAYS.forEach { assertThat(PushPreference.allows(settings, it)).isTrue() }
    }

    @Test
    fun `a category without an entry is on`() {
        val settings = preference(categories = mapOf("FOLLOW" to false))

        assertThat(PushPreference.allows(settings, NotificationType.BUNDLE_LIKED)).isTrue()
    }

    @Test
    fun `the always sent types ignore every category`() {
        val settings = preference(categories = PushCategory.entries.associate { it.name to false })

        PushCategory.ALWAYS.forEach { assertThat(PushPreference.allows(settings, it)).isTrue() }
    }

    private fun preference(
        enabled: Boolean = true,
        categories: Map<String, Boolean> = emptyMap()
    ) = PushPreference(
        memberId = 1,
        enabled = enabled,
        categories = categories,
        updatedAt = Instant.EPOCH
    )
}
