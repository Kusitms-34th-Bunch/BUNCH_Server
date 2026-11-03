package com.bunch.notification

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V11 then V12 migrates retained join notifications without creating push tables`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema, "번치 주인")
            val joinedMemberId = insertMember(schema, "참여한 멤버")
            val expiredRecipientId = insertMember(schema, "오래된 수신자")
            val bunchId = insertBunch(schema, ownerId)
            insertMembership(schema, bunchId, ownerId, "OWNER")
            val joinedMembershipId = insertMembership(schema, bunchId, joinedMemberId)
            val retainedId = insertLegacyNotification(
                schema,
                bunchId,
                joinedMembershipId,
                ownerId,
                joinedMemberId,
                "10 days"
            )
            val retainedCreatedAt = notificationCreatedAt(schema, "bunch_join_notifications", retainedId)
            val expiredId = insertLegacyNotification(
                schema,
                bunchId,
                joinedMembershipId,
                expiredRecipientId,
                joinedMemberId,
                "31 days"
            )

            assertThat(configure(schema, "11").migrate().migrationsExecuted).isEqualTo(1)
            val current = configure(schema, "12")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()

            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.notifications",
                Long::class.java
            )).isEqualTo(1)
            assertThat(jdbc.queryForObject(
                "SELECT id FROM $schema.notifications",
                Long::class.java
            )).isEqualTo(retainedId)
            assertThat(notificationCreatedAt(schema, "notifications", retainedId)).isEqualTo(retainedCreatedAt)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.notifications WHERE id = ?",
                Long::class.java,
                expiredId
            )).isZero()

            assertThat(jdbc.queryForObject(
                "SELECT type FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo("MEMBER_JOINED")
            assertThat(columnLength(schema, "notifications", "type")).isEqualTo(40)
            assertThat(jdbc.queryForObject(
                "SELECT dedupe_key FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo("MEMBER_JOINED:$joinedMembershipId")
            assertThat(jdbc.queryForObject(
                "SELECT title FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo("새 멤버가 들어왔어요")
            assertThat(jdbc.queryForObject(
                "SELECT body FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo("참여한 멤버님이 번치에 참여했어요.")
            assertThat(jdbc.queryForObject(
                "SELECT target->>'kind' FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo("MEMBERS")
            assertThat(jdbc.queryForObject(
                "SELECT target->>'bunchId' FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo(bunchId.toString())
            assertThat(jdbc.queryForObject(
                "SELECT activity_data IS NULL AND group_until IS NULL AND read_at IS NULL FROM $schema.notifications WHERE id = ?",
                Boolean::class.java,
                retainedId
            )).isTrue()

            jdbc.update("UPDATE $schema.members SET nickname = '바뀐 멤버' WHERE id = ?", joinedMemberId)
            assertThat(jdbc.queryForObject(
                "SELECT body FROM $schema.notifications WHERE id = ?",
                String::class.java,
                retainedId
            )).isEqualTo("참여한 멤버님이 번치에 참여했어요.")

            assertThat(columnCount(schema, "notifications", "joined_membership_id")).isZero()
            assertThat(columnCount(schema, "notifications", "joined_member_id")).isZero()
            assertThat(tableCount(schema, "bunch_join_notifications")).isZero()
            assertThat(tableCount(schema, "push_jobs")).isZero()
            assertThat(tableCount(schema, "push_devices")).isZero()
            assertThat(tableCount(schema, "push_preferences")).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.flyway_schema_history WHERE version = '11' AND success",
                Long::class.java
            )).isEqualTo(1)

            val nextId = insertNotification(
                schema,
                ownerId,
                bunchId,
                "DRAFT_ACTIVITY",
                "DRAFT_ACTIVITY:1"
            )
            assertThat(nextId).isEqualTo(expiredId + 1)

            jdbc.update("DELETE FROM $schema.bunch_memberships WHERE id = ?", joinedMembershipId)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.notifications WHERE id = ?",
                Long::class.java,
                retainedId
            )).isEqualTo(1)

            assertThat(current.migrate().migrationsExecuted).isZero()
            current.validate()
        }
    }

    @Test
    fun `notifications enforce the 21-type contract, app exclusions, uniqueness, and cascade foreign keys`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema, "번치 주인")
            val recipientId = insertMember(schema, "수신자")
            val otherRecipientId = insertMember(schema, "다른 수신자")
            val joinedMemberId = insertMember(schema, "참여자")
            val bunchId = insertBunch(schema, ownerId)
            insertMembership(schema, bunchId, ownerId, "OWNER")
            val joinedMembershipId = insertMembership(schema, bunchId, joinedMemberId)
            configure(schema, "11").migrate()
            configure(schema, "12").migrate()

            val appTypes = listOf(
                "DRAFT_PARTICIPANT_ADDED",
                "DRAFT_ACTIVITY",
                "COMMENT_REMINDER",
                "DRAFT_PARTICIPANT_REMOVED",
                "DRAFT_READY_TO_PUBLISH",
                "DRAFT_PARTICIPANTS_INSUFFICIENT",
                "BUNDLE_PUBLISHED",
                "CANDIDATE_CREATED",
                "CHARACTER_GROWN",
                "MEMBER_JOINED",
                "CO_OWNER_GRANTED",
                "CO_OWNER_REVOKED",
                "OWNER_TRANSFERRED",
                "BUNCH_VISIBILITY_CHANGED",
                "BUNCH_FOLLOWED",
                "FOLLOW_REQUESTED",
                "FOLLOW_REQUEST_APPROVED",
                "SOCIAL_COMMENT_CREATED",
                "BUNDLE_LIKED"
            )
            appTypes.forEach { type ->
                insertNotification(schema, recipientId, bunchId, type, "$type:1")
            }

            assertThatThrownBy {
                insertNotification(schema, recipientId, bunchId, "MEMBER_REMOVED", "MEMBER_REMOVED:1")
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertNotification(schema, recipientId, bunchId, "BUNCH_DELETED", "BUNCH_DELETED:1")
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertNotification(schema, recipientId, bunchId, "NOT_A_NOTIFICATION", "unknown:1")
            }.isInstanceOf(DataAccessException::class.java)

            assertThatThrownBy {
                insertNotification(schema, recipientId, bunchId, "DRAFT_ACTIVITY", "DRAFT_ACTIVITY:1")
            }.isInstanceOf(DataAccessException::class.java)
            insertNotification(schema, otherRecipientId, bunchId, "DRAFT_ACTIVITY", "DRAFT_ACTIVITY:1")

            assertThatThrownBy {
                jdbc.update(
                    """INSERT INTO $schema.notifications
                        (recipient_member_id, bunch_id, type, dedupe_key, title, body, target)
                        VALUES (?, ?, 'DRAFT_ACTIVITY', ?, ?, ?, CAST(? AS jsonb))""",
                    recipientId,
                    bunchId,
                    "null-title:1",
                    null,
                    "본문",
                    "{}"
                )
            }.isInstanceOf(DataAccessException::class.java)

            val retainedId = insertNotification(
                schema,
                recipientId,
                bunchId,
                "MEMBER_JOINED",
                "membership:$joinedMembershipId"
            )
            jdbc.update("DELETE FROM $schema.bunch_memberships WHERE id = ?", joinedMembershipId)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.notifications WHERE id = ?",
                Long::class.java,
                retainedId
            )).isEqualTo(1)

            val recipientNotificationId = insertNotification(
                schema,
                otherRecipientId,
                bunchId,
                "DRAFT_ACTIVITY",
                "recipient-cascade:1"
            )
            jdbc.update("DELETE FROM $schema.members WHERE id = ?", otherRecipientId)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.notifications WHERE id = ?",
                Long::class.java,
                recipientNotificationId
            )).isZero()

            val bunchNotificationId = insertNotification(
                schema,
                recipientId,
                bunchId,
                "DRAFT_ACTIVITY",
                "bunch-cascade:1"
            )
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", bunchId)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.notifications WHERE id IN (?, ?)",
                Long::class.java,
                retainedId,
                bunchNotificationId
            )).isZero()
        }
    }

    private fun withTarget10(block: (String) -> Unit) {
        val schema = "notification_migration_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val legacy = configure(schema, "10")
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(10)
            block(schema)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun configure(schema: String, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun insertMember(schema: String, nickname: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', ?) RETURNING id""",
        Long::class.java,
        nickname
    )!!

    private fun insertBunch(schema: String, ownerId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, 'migration', 'PRIVATE', 'TYPE_2', ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        "notification-bunch-${UUID.randomUUID()}",
        ownerId,
        UUID.randomUUID(),
        "notification-fingerprint-${UUID.randomUUID()}"
    )!!

    private fun insertMembership(schema: String, bunchId: Long, memberId: Long, role: String = "MEMBER"): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_memberships
            (bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at, join_requests)
            VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::jsonb)
            RETURNING id""",
        Long::class.java,
        bunchId,
        memberId,
        role
    )!!

    private fun insertLegacyNotification(
        schema: String,
        bunchId: Long,
        joinedMembershipId: Long,
        recipientMemberId: Long,
        joinedMemberId: Long,
        age: String
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_join_notifications
            (bunch_id, joined_membership_id, recipient_member_id, joined_member_id, created_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP - CAST(? AS INTERVAL))
            RETURNING id""",
        Long::class.java,
        bunchId,
        joinedMembershipId,
        recipientMemberId,
        joinedMemberId,
        age
    )!!

    private fun insertNotification(
        schema: String,
        recipientMemberId: Long,
        bunchId: Long,
        type: String,
        dedupeKey: String
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.notifications
            (recipient_member_id, bunch_id, type, dedupe_key, title, body, target, created_at)
            VALUES (?, ?, ?, ?, '제목', '본문', CAST(? AS jsonb), CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        recipientMemberId,
        bunchId,
        type,
        dedupeKey,
        "{\"kind\":\"MEMBERS\",\"bunchId\":$bunchId}"
    )!!

    private fun notificationCreatedAt(schema: String, table: String, id: Long): Instant = jdbc.queryForObject(
        "SELECT created_at FROM $schema.$table WHERE id = ?",
        Timestamp::class.java,
        id
    )!!.toInstant()

    private fun columnCount(schema: String, table: String, column: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
        Long::class.java,
        schema,
        table,
        column
    )!!

    private fun columnLength(schema: String, table: String, column: String): Int = jdbc.queryForObject(
        """SELECT character_maximum_length FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
        Int::class.java,
        schema,
        table,
        column
    )!!

    private fun tableCount(schema: String, table: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?""",
        Long::class.java,
        schema,
        table
    )!!
}
