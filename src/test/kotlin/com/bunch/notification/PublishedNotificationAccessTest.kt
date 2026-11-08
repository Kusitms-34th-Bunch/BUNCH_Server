package com.bunch.notification

import com.bunch.bunch.service.BunchAccessNoticeService
import com.bunch.feed.domain.FeedAccessRepository
import com.bunch.feed.dto.FeedViewer
import com.bunch.feed.service.FeedAccessService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.notification.domain.NotificationTargetKind
import com.bunch.social.domain.SocialCommentRepository
import com.bunch.notification.dto.NotificationTarget
import com.bunch.notification.service.PublishedNotificationAccess
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException

class PublishedNotificationAccessTest {
    @Test
    fun `opening does not swallow other business errors or storage failures`() {
        val feedAccess = mock(FeedAccessService::class.java)
        val repository = mock(FeedAccessRepository::class.java)
        val notices = mock(BunchAccessNoticeService::class.java)
        val access = PublishedNotificationAccess(feedAccess, repository, mock(SocialCommentRepository::class.java), notices)
        val target = NotificationTarget(NotificationTargetKind.BUNDLE, bunchId = 2, targetBunchId = 3)
        `when`(feedAccess.viewer(1)).thenReturn(FeedViewer(1, 2))

        listOf(
            BusinessException(ErrorCode.FORBIDDEN),
            DataAccessResourceFailureException("notice store unavailable")
        ).forEach { failure ->
            doThrow(failure).`when`(notices).requireNoticeIfPresent(1, 3)
            assertThatThrownBy { access.canOpen(1, target) }.isSameAs(failure)
        }
    }
}
