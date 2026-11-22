package com.bunch.notification

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import javax.sql.DataSource

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationDeepLinkMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V53 backfills notification and pending push deep links without touching finished jobs`() {
        val schema = "notification_deep_link_" + UUID.randomUUID().toString().replace("-", "")
        try {
            flyway(schema, "52").migrate()
            jdbc.execute("ALTER FUNCTION $schema.validate_social_comment_parent() SET search_path = $schema")

            val member = insertMember(schema)
            val bunch = insertBunch(schema, member)
            val bundle = insertBundle(schema, bunch, member)
            val root = insertComment(schema, bundle, bunch, member, "root")
            val reply = insertComment(schema, bundle, bunch, member, "reply", root)
            val deletedParent = insertComment(schema, bundle, bunch, member, "deleted parent")
            jdbc.update(
                "UPDATE $schema.social_comments SET content = '', deleted_at = created_at + interval '1 minute' " +
                    "WHERE id = ?",
                deletedParent
            )
            val replyToDeletedParent = insertComment(
                schema,
                bundle,
                bunch,
                member,
                "reply to deleted parent",
                deletedParent
            )

            val rootNotification = insertNotification(
                schema,
                member,
                bunch,
                "root",
                """{"kind":"BUNDLE_COMMENTS","bunchId":$bunch,"bundleId":$bundle,"commentId":$root,"parentCommentId":999999}"""
            )
            val replyNotification = insertNotification(
                schema,
                member,
                bunch,
                "reply",
                commentTarget(bunch, bundle, reply)
            )
            val deletedParentReplyNotification = insertNotification(
                schema,
                member,
                bunch,
                "deleted-parent-reply",
                commentTarget(bunch, bundle, replyToDeletedParent)
            )
            val missingCommentNotification = insertNotification(
                schema,
                member,
                bunch,
                "missing-comment",
                commentTarget(bunch, bundle, Long.MAX_VALUE)
            )
            val missingCommentIdNotification = insertNotification(
                schema,
                member,
                bunch,
                "missing-comment-id",
                """{"kind":"BUNDLE_COMMENTS","bunchId":$bunch,"bundleId":$bundle}"""
            )
            val nonCommentNotification = insertNotification(
                schema,
                member,
                bunch,
                "non-comment",
                """{"kind":"BUNDLE","bunchId":$bunch,"bundleId":$bundle}"""
            )
            val malformedCommentNotification = insertNotification(
                schema,
                member,
                bunch,
                "malformed-comment-id",
                """{"kind":"BUNDLE_COMMENTS","bunchId":$bunch,"bundleId":"not-a-bundle","commentId":"not-a-comment"}"""
            )

            val pendingReplyJob = insertPushJob(
                schema,
                member,
                bunch,
                replyNotification,
                "pending-reply",
                commentTarget(bunch, bundle, reply) + ",\"notificationId\":999999"
            )
            val pendingDeletedParentReplyJob = insertPushJob(
                schema,
                member,
                bunch,
                deletedParentReplyNotification,
                "pending-deleted-parent-reply",
                commentTarget(bunch, bundle, replyToDeletedParent)
            )
            val pendingDetachedJob = insertPushJob(
                schema,
                member,
                bunch,
                null,
                "pending-detached",
                """{"kind":"ACCESS_NOTICE","bunchId":$bunch,"noticeId":77},"notificationId":999999""",
                "MEMBER_REMOVED"
            )
            val finishedSentJob = insertPushJob(
                schema,
                member,
                bunch,
                replyNotification,
                "finished-sent",
                commentTarget(bunch, bundle, reply) + ",\"parentCommentId\":123456,\"notificationId\":888888",
                status = "SENT"
            )
            val finishedFailedJob = insertPushJob(
                schema,
                member,
                bunch,
                null,
                "finished-failed",
                """{"kind":"BUNDLE","bunchId":$bunch},"notificationId":888888""",
                status = "FAILED"
            )

            val notificationSnapshots = jdbc.queryForList(
                "SELECT id, title, body FROM $schema.notifications ORDER BY id"
            )
            val finishedJobSnapshots = jdbc.queryForList(
                """SELECT id::text AS id, status, attempts, payload::text AS payload, sent_at, last_error_code
                    FROM $schema.push_jobs WHERE status <> 'PENDING' ORDER BY id::text"""
            )

            val migration = flyway(schema, "53")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)

            mapOf<Long, Long?>(
                rootNotification to null,
                replyNotification to root,
                deletedParentReplyNotification to deletedParent,
                missingCommentNotification to null,
                missingCommentIdNotification to null,
                nonCommentNotification to null,
                malformedCommentNotification to null
            ).forEach { (notificationId, parentCommentId) ->
                assertNotificationParent(schema, notificationId, parentCommentId)
            }

            assertPendingPushJob(schema, pendingReplyJob, root, replyNotification)
            assertPendingPushJob(schema, pendingDeletedParentReplyJob, deletedParent, deletedParentReplyNotification)
            assertPendingPushJob(schema, pendingDetachedJob, null, null)
            assertThat(jdbc.queryForObject(
                "SELECT status FROM $schema.push_jobs WHERE id = ?",
                String::class.java,
                pendingReplyJob
            )).isEqualTo("PENDING")
            assertThat(jdbc.queryForObject(
                "SELECT payload #>> '{aps,alert,title}' FROM $schema.push_jobs WHERE id = ?",
                String::class.java,
                pendingReplyJob
            )).isEqualTo("old title")
            assertThat(jdbc.queryForObject(
                "SELECT payload #>> '{aps,alert,body}' FROM $schema.push_jobs WHERE id = ?",
                String::class.java,
                pendingReplyJob
            )).isEqualTo("old body")

            assertThat(jdbc.queryForList(
                "SELECT id, title, body FROM $schema.notifications ORDER BY id"
            )).isEqualTo(notificationSnapshots)
            assertThat(jdbc.queryForList(
                """SELECT id::text AS id, status, attempts, payload::text AS payload, sent_at, last_error_code
                    FROM $schema.push_jobs WHERE status <> 'PENDING' ORDER BY id::text"""
            )).isEqualTo(finishedJobSnapshots)

            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun assertNotificationParent(schema: String, notificationId: Long, parentCommentId: Long?) {
        assertThat(jdbc.queryForObject(
            "SELECT jsonb_exists(target, 'parentCommentId') FROM $schema.notifications WHERE id = ?",
            Boolean::class.java,
            notificationId
        )).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT target ->> 'parentCommentId' FROM $schema.notifications WHERE id = ?",
            String::class.java,
            notificationId
        )).isEqualTo(parentCommentId?.toString())
    }

    private fun assertPendingPushJob(
        schema: String,
        jobId: UUID,
        parentCommentId: Long?,
        notificationId: Long?
    ) {
        assertThat(jdbc.queryForObject(
            "SELECT jsonb_exists(payload -> 'target', 'parentCommentId') FROM $schema.push_jobs WHERE id = ?",
            Boolean::class.java,
            jobId
        )).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT payload -> 'target' ->> 'parentCommentId' FROM $schema.push_jobs WHERE id = ?",
            String::class.java,
            jobId
        )).isEqualTo(parentCommentId?.toString())
        assertThat(jdbc.queryForObject(
            "SELECT jsonb_exists(payload, 'notificationId') FROM $schema.push_jobs WHERE id = ?",
            Boolean::class.java,
            jobId
        )).isTrue()
        assertThat(jdbc.queryForObject(
            "SELECT payload ->> 'notificationId' FROM $schema.push_jobs WHERE id = ?",
            String::class.java,
            jobId
        )).isEqualTo(notificationId?.toString())
    }

    private fun insertMember(schema: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', 'migration member') RETURNING id""",
        Long::class.java
    )!!

    private fun insertBunch(schema: String, memberId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        "notification-deep-link-${UUID.randomUUID()}",
        memberId,
        UUID.randomUUID(),
        "notification-deep-link-${UUID.randomUUID()}"
    )!!

    private fun insertBundle(schema: String, bunchId: Long, memberId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bundles
            (bunch_id, created_by, status, title, visibility, created_at, updated_at, published_at)
            VALUES (?, ?, 'PUBLISHED', 'migration bundle', 'PUBLIC', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        bunchId,
        memberId
    )!!

    private fun insertComment(
        schema: String,
        bundleId: Long,
        bunchId: Long,
        memberId: Long,
        content: String,
        parentId: Long? = null
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.social_comments
            (bundle_id, author_member_id, acting_bunch_id, badge_name, badge_character_type,
             parent_id, content, request_key, fingerprint, created_at)
            VALUES (?, ?, ?, 'migration', 'TYPE_2', ?, ?, ?, 'migration-fingerprint', CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        bundleId,
        memberId,
        bunchId,
        parentId,
        content,
        UUID.randomUUID()
    )!!

    private fun insertNotification(
        schema: String,
        memberId: Long,
        bunchId: Long,
        dedupeKey: String,
        target: String
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.notifications
            (recipient_member_id, bunch_id, type, dedupe_key, title, body, target, created_at)
            VALUES (?, ?, 'SOCIAL_COMMENT_CREATED', ?, 'old title', 'old body', CAST(? AS jsonb), CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        memberId,
        bunchId,
        dedupeKey,
        target
    )!!

    private fun insertPushJob(
        schema: String,
        memberId: Long,
        bunchId: Long,
        notificationId: Long?,
        dedupeKey: String,
        targetAndOptionalFields: String,
        type: String = "SOCIAL_COMMENT_CREATED",
        status: String = "PENDING"
    ): UUID {
        val jobId = UUID.randomUUID()
        val payload = """{"aps":{"alert":{"title":"old title","body":"old body"},"sound":"default"},"eventId":"$jobId","type":"$type","target":$targetAndOptionalFields}"""
        jdbc.update(
            """INSERT INTO $schema.push_jobs
                (id, recipient_member_id, session_id, bunch_id, notification_id, dedupe_key, type, payload,
                 available_at, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + interval '1 hour', CURRENT_TIMESTAMP)""",
            jobId,
            memberId,
            UUID.randomUUID(),
            bunchId,
            notificationId,
            dedupeKey,
            type,
            payload
        )
        if (status != "PENDING") {
            val sentAt = if (status == "SENT") ", sent_at = CURRENT_TIMESTAMP" else ""
            jdbc.execute("UPDATE $schema.push_jobs SET status = '$status'$sentAt WHERE id = '$jobId'")
        }
        return jobId
    }

    private fun commentTarget(bunchId: Long, bundleId: Long, commentId: Long): String =
        """{"kind":"BUNDLE_COMMENTS","bunchId":$bunchId,"bundleId":$bundleId,"commentId":$commentId}"""

    private fun flyway(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
