package com.bunch.notification.domain.push

import com.bunch.notification.domain.NotificationType

// The four toggles of the my page notification settings (7.3.3). The names are the API keys.
enum class PushCategory(val types: Set<NotificationType>) {
    BUNDLE_NEWS(
        setOf(
            NotificationType.DRAFT_PARTICIPANT_ADDED,
            NotificationType.DRAFT_ACTIVITY,
            NotificationType.COMMENT_REMINDER,
            NotificationType.DRAFT_PARTICIPANT_REMOVED,
            NotificationType.DRAFT_READY_TO_PUBLISH,
            NotificationType.DRAFT_PARTICIPANTS_INSUFFICIENT,
            NotificationType.BUNDLE_PUBLISHED,
            NotificationType.CANDIDATE_CREATED
        )
    ),
    MEMBER_ROLE(
        setOf(
            NotificationType.MEMBER_JOINED,
            NotificationType.CO_OWNER_GRANTED,
            NotificationType.CO_OWNER_REVOKED,
            NotificationType.OWNER_TRANSFERRED
        )
    ),
    FOLLOW(
        setOf(
            NotificationType.BUNCH_FOLLOWED,
            NotificationType.FOLLOW_REQUESTED,
            NotificationType.FOLLOW_REQUEST_APPROVED
        )
    ),
    REACTION(
        setOf(
            NotificationType.SOCIAL_COMMENT_CREATED,
            NotificationType.BUNDLE_LIKED
        )
    );

    companion object {
        // Pushed whenever the app push toggle is on and never switched off one by one (7.3.2):
        // removed from a bunch (14), bunch deleted (15) and bunch visibility changed (16).
        val ALWAYS: Set<NotificationType> = setOf(
            NotificationType.MEMBER_REMOVED,
            NotificationType.BUNCH_DELETED,
            NotificationType.BUNCH_VISIBILITY_CHANGED
        )

        fun of(type: NotificationType): PushCategory? = entries.firstOrNull { type in it.types }
    }
}
