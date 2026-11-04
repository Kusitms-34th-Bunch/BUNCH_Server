package com.bunch.bunch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataAccessException
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
class BunchSocialMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `bunch social migrations enforce relationship constraints and indexes`() {
        withTarget10 { schema ->
            val creator = insertMember(schema, "social-creator")
            val approver = insertMember(schema, "social-approver")
            val actor = insertMember(schema, "social-actor")
            val reporter = insertMember(schema, "social-reporter")
            val newBunch = { suffix: String ->
                val bunchId = insertBunch(schema, "social-$suffix", creator)
                insertOwnerMembership(schema, bunchId, creator)
                bunchId
            }

            val pendingFollower = newBunch("pending-follower")
            val pendingFollowed = newBunch("pending-followed")
            val approvedFollower = newBunch("approved-follower")
            val approvedFollowed = newBunch("approved-followed")
            val pendingWithApprovalFollower = newBunch("pending-with-approval-follower")
            val pendingWithApprovalFollowed = newBunch("pending-with-approval-followed")
            val approvedWithoutTimeFollower = newBunch("approved-without-time-follower")
            val approvedWithoutTimeFollowed = newBunch("approved-without-time-followed")
            val invalidStatusFollower = newBunch("invalid-status-follower")
            val invalidStatusFollowed = newBunch("invalid-status-followed")
            val selfBunch = newBunch("self")
            val actorFollower = newBunch("actor-follower")
            val actorFollowed = newBunch("actor-followed")
            val followerCascadeFollower = newBunch("follower-cascade-follower")
            val followerCascadeFollowed = newBunch("follower-cascade-followed")
            val followedCascadeFollower = newBunch("followed-cascade-follower")
            val followedCascadeFollowed = newBunch("followed-cascade-followed")

            assertThat(jdbc.queryForObject(
                """SELECT count(*) FROM $schema.bunches bunch
                    WHERE NOT EXISTS (
                        SELECT 1 FROM $schema.bunch_memberships membership
                        WHERE membership.bunch_id = bunch.id
                          AND membership.status = 'ACTIVE'
                          AND membership.role = 'OWNER')""",
                Long::class.java
            )).isZero()
            assertThat(configure(schema, "11").migrate().migrationsExecuted).isEqualTo(1)
            assertThat(configure(schema, "12").migrate().migrationsExecuted).isEqualTo(1)
            assertThat(configure(schema, "13").migrate().migrationsExecuted).isEqualTo(1)

            val preV14Members = jdbc.queryForList("SELECT * FROM $schema.members ORDER BY id")
            val preV14Bunches = jdbc.queryForList("SELECT * FROM $schema.bunches ORDER BY id")
            val preV14Memberships = jdbc.queryForList(
                "SELECT * FROM $schema.bunch_memberships ORDER BY id"
            )
            val migration = configure(schema, "14")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.members ORDER BY id"))
                .isEqualTo(preV14Members)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.bunches ORDER BY id"))
                .isEqualTo(preV14Bunches)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.bunch_memberships ORDER BY id"))
                .isEqualTo(preV14Memberships)

            assertThat(columnNames(schema, "bunch_follows")).containsExactlyInAnyOrder(
                "id",
                "follower_bunch_id",
                "followed_bunch_id",
                "status",
                "created_by_member_id",
                "approved_by_member_id",
                "created_at",
                "approved_at"
            )
            assertThat(indexCount(schema, "bunch_follows", "ix_bunch_follow_requests")).isEqualTo(1)
            assertThat(indexCount(schema, "bunch_follows", "ix_bunch_followers")).isEqualTo(1)
            assertThat(indexCount(schema, "bunch_follows", "ix_bunch_following")).isEqualTo(1)
            assertThat(columnNames(schema, "bunch_blocks")).containsExactlyInAnyOrder(
                "id",
                "blocker_bunch_id",
                "blocked_bunch_id",
                "created_by_member_id",
                "created_at"
            )
            assertThat(indexCount(schema, "bunch_blocks", "ix_bunch_blocks")).isEqualTo(1)
            assertThat(indexCount(schema, "bunch_blocks", "ix_bunch_blocks_reverse")).isEqualTo(1)
            assertThat(columnNames(schema, "bunch_profile_reports")).containsExactlyInAnyOrder(
                "id",
                "reporter_bunch_id",
                "target_bunch_id",
                "reported_by_member_id",
                "reason",
                "detail",
                "status",
                "created_at",
                "reviewed_at"
            )
            assertThat(columnDefinition(schema, "bunch_profile_reports", "reason"))
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 32)
                .containsEntry("is_nullable", "NO")
            assertThat(columnDefinition(schema, "bunch_profile_reports", "detail"))
                .containsEntry("data_type", "text")
                .containsEntry("is_nullable", "YES")
            assertThat(columnDefinition(schema, "bunch_profile_reports", "status"))
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 16)
                .containsEntry("is_nullable", "NO")
            assertThat(columnDefinition(schema, "bunch_profile_reports", "status")["column_default"].toString())
                .contains("RECEIVED")
            assertThat(columnDefinition(schema, "bunch_profile_reports", "reviewed_at"))
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES")

            val pendingId = insertFollow(
                schema,
                pendingFollower,
                pendingFollowed,
                status = "PENDING",
                createdByMemberId = creator
            )
            assertThat(jdbc.queryForObject(
                "SELECT status FROM $schema.bunch_follows WHERE id = ?",
                String::class.java,
                pendingId
            )).isEqualTo("PENDING")
            assertThat(jdbc.queryForObject(
                "SELECT approved_at IS NULL FROM $schema.bunch_follows WHERE id = ?",
                Boolean::class.java,
                pendingId
            )).isTrue()

            assertThatThrownBy {
                insertFollow(
                    schema,
                    pendingFollower,
                    pendingFollowed,
                    status = "APPROVED",
                    createdByMemberId = creator,
                    approvedByMemberId = approver,
                    approvedAt = APPROVED_AT
                )
            }.isInstanceOf(DataAccessException::class.java)

            val approvedId = insertFollow(
                schema,
                approvedFollower,
                approvedFollowed,
                status = "APPROVED",
                createdByMemberId = creator,
                approvedByMemberId = approver,
                approvedAt = APPROVED_AT
            )
            assertThat(jdbc.queryForObject(
                "SELECT status FROM $schema.bunch_follows WHERE id = ?",
                String::class.java,
                approvedId
            )).isEqualTo("APPROVED")
            assertThat(jdbc.queryForObject(
                "SELECT approved_at IS NOT NULL FROM $schema.bunch_follows WHERE id = ?",
                Boolean::class.java,
                approvedId
            )).isTrue()

            assertThatThrownBy {
                insertFollow(
                    schema,
                    pendingWithApprovalFollower,
                    pendingWithApprovalFollowed,
                    status = "PENDING",
                    createdByMemberId = creator,
                    approvedAt = APPROVED_AT
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertFollow(
                    schema,
                    approvedWithoutTimeFollower,
                    approvedWithoutTimeFollowed,
                    status = "APPROVED",
                    createdByMemberId = creator,
                    approvedByMemberId = approver
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertFollow(
                    schema,
                    invalidStatusFollower,
                    invalidStatusFollowed,
                    status = "DECLINED",
                    createdByMemberId = creator
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertFollow(
                    schema,
                    selfBunch,
                    selfBunch,
                    status = "PENDING",
                    createdByMemberId = creator
                )
            }.isInstanceOf(DataAccessException::class.java)

            val actorFollowId = insertFollow(
                schema,
                actorFollower,
                actorFollowed,
                status = "PENDING",
                createdByMemberId = actor
            )
            assertThatThrownBy { jdbc.update("DELETE FROM $schema.members WHERE id = ?", actor) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_follows WHERE id = ?",
                Long::class.java,
                actorFollowId
            )).isEqualTo(1)

            jdbc.update("DELETE FROM $schema.members WHERE id = ?", approver)
            assertThat(jdbc.queryForObject(
                "SELECT approved_by_member_id FROM $schema.bunch_follows WHERE id = ?",
                Long::class.java,
                approvedId
            )).isNull()

            val actorBlockId = insertBlock(schema, actorFollower, actorFollowed, actor)
            assertThatThrownBy {
                insertBlock(schema, actorFollower, actorFollowed, actor)
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertBlock(schema, selfBunch, selfBunch, creator)
            }.isInstanceOf(DataAccessException::class.java)
            jdbc.update("DELETE FROM $schema.bunch_follows WHERE id = ?", actorFollowId)
            assertThatThrownBy { jdbc.update("DELETE FROM $schema.members WHERE id = ?", actor) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_blocks WHERE id = ?",
                Long::class.java,
                actorBlockId
            )).isEqualTo(1)
            jdbc.update("DELETE FROM $schema.bunch_blocks WHERE id = ?", actorBlockId)
            jdbc.update("DELETE FROM $schema.members WHERE id = ?", actor)

            val reportId = insertReport(
                schema,
                approvedFollower,
                approvedFollowed,
                reporter,
                reason = "SPAM",
                detail = "migration detail"
            )
            assertThat(jdbc.queryForObject(
                "SELECT status FROM $schema.bunch_profile_reports WHERE id = ?",
                String::class.java,
                reportId
            )).isEqualTo("RECEIVED")
            assertThat(jdbc.queryForObject(
                "SELECT detail FROM $schema.bunch_profile_reports WHERE id = ?",
                String::class.java,
                reportId
            )).isEqualTo("migration detail")
            jdbc.update("UPDATE $schema.bunch_profile_reports SET detail = NULL WHERE id = ?", reportId)
            assertThat(jdbc.queryForObject(
                "SELECT detail IS NULL FROM $schema.bunch_profile_reports WHERE id = ?",
                Boolean::class.java,
                reportId
            )).isTrue()
            listOf("SPAM", "INAPPROPRIATE", "HARASSMENT", "PERSONAL_INFORMATION", "OTHER").forEach { reason ->
                jdbc.update(
                    "UPDATE $schema.bunch_profile_reports SET reason = ? WHERE id = ?",
                    reason,
                    reportId
                )
            }
            listOf("RECEIVED", "REVIEWING", "ACTIONED", "DISMISSED").forEach { status ->
                jdbc.update(
                    "UPDATE $schema.bunch_profile_reports SET status = ? WHERE id = ?",
                    status,
                    reportId
                )
            }
            assertThatThrownBy {
                insertReport(schema, invalidStatusFollower, invalidStatusFollowed, reporter, reason = "NOT_A_REASON")
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertReport(schema, selfBunch, selfBunch, reporter, reason = "SPAM")
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                jdbc.update(
                    """INSERT INTO $schema.bunch_profile_reports
                        (reporter_bunch_id, target_bunch_id, reported_by_member_id, reason, status, created_at)
                        VALUES (?, ?, ?, 'SPAM', 'NOT_A_STATUS', CURRENT_TIMESTAMP)""",
                    pendingWithApprovalFollower,
                    pendingWithApprovalFollowed,
                    reporter
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { insertReport(schema, approvedFollower, approvedFollowed, reporter, reason = "OTHER") }
                .isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { jdbc.update("DELETE FROM $schema.members WHERE id = ?", reporter) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_profile_reports WHERE id = ?",
                Long::class.java,
                reportId
            )).isEqualTo(1)
            jdbc.update("DELETE FROM $schema.bunch_profile_reports WHERE id = ?", reportId)
            jdbc.update("DELETE FROM $schema.members WHERE id = ?", reporter)

            val followerCascadeId = insertFollow(
                schema,
                followerCascadeFollower,
                followerCascadeFollowed,
                status = "PENDING",
                createdByMemberId = creator
            )
            val followedCascadeId = insertFollow(
                schema,
                followedCascadeFollower,
                followedCascadeFollowed,
                status = "PENDING",
                createdByMemberId = creator
            )
            val blockCascadeByBlockerId = insertBlock(
                schema,
                followerCascadeFollower,
                followerCascadeFollowed,
                creator
            )
            val blockCascadeByBlockedId = insertBlock(
                schema,
                followedCascadeFollower,
                followedCascadeFollowed,
                creator
            )
            val reportCascadeByReporterId = insertReport(
                schema,
                followerCascadeFollower,
                followerCascadeFollowed,
                creator,
                reason = "OTHER"
            )
            val reportCascadeByTargetId = insertReport(
                schema,
                followedCascadeFollower,
                followedCascadeFollowed,
                creator,
                reason = "OTHER"
            )
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", followerCascadeFollower)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_follows WHERE id = ?",
                Long::class.java,
                followerCascadeId
            )).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_blocks WHERE id = ?",
                Long::class.java,
                blockCascadeByBlockerId
            )).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_profile_reports WHERE id = ?",
                Long::class.java,
                reportCascadeByReporterId
            )).isZero()
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", followedCascadeFollowed)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_follows WHERE id = ?",
                Long::class.java,
                followedCascadeId
            )).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_blocks WHERE id = ?",
                Long::class.java,
                blockCascadeByBlockedId
            )).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_profile_reports WHERE id = ?",
                Long::class.java,
                reportCascadeByTargetId
            )).isZero()

            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
        }
    }

    private fun withTarget10(block: (String) -> Unit) {
        val schema = buildString {
            append("bunch_social_migration_")
            append(UUID.randomUUID().toString().replace("-", ""))
        }
        try {
            assertThat(configure(schema, "10").migrate().migrationsExecuted).isEqualTo(10)
            block(schema)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()

    private fun insertMember(schema: String, nickname: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members
            (role, signup_status, account_status, nickname)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', ?)
            RETURNING id""",
        Long::class.java,
        nickname
    )!!

    private fun insertBunch(schema: String, name: String, createdBy: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, '함께 기록해요', 'PRIVATE', 'TYPE_2', ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        name,
        createdBy,
        UUID.randomUUID(),
        "fingerprint-$name"
    )!!

    private fun insertOwnerMembership(schema: String, bunchId: Long, memberId: Long) {
        jdbc.update(
            """INSERT INTO $schema.bunch_memberships
                (bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at, join_requests)
                VALUES (?, ?, 'OWNER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, '{}'::jsonb)""",
            bunchId,
            memberId
        )
    }

    private fun insertFollow(
        schema: String,
        followerBunchId: Long,
        followedBunchId: Long,
        status: String,
        createdByMemberId: Long,
        approvedByMemberId: Long? = null,
        approvedAt: String? = null
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_follows
            (follower_bunch_id, followed_bunch_id, status, created_by_member_id,
             approved_by_member_id, created_at, approved_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                    CAST(? AS TIMESTAMP WITH TIME ZONE))
            RETURNING id""",
        Long::class.java,
        followerBunchId,
        followedBunchId,
        status,
        createdByMemberId,
        approvedByMemberId,
        approvedAt
    )!!

    private fun insertBlock(
        schema: String,
        blockerBunchId: Long,
        blockedBunchId: Long,
        createdByMemberId: Long
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_blocks
            (blocker_bunch_id, blocked_bunch_id, created_by_member_id, created_at)
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        blockerBunchId,
        blockedBunchId,
        createdByMemberId
    )!!

    private fun insertReport(
        schema: String,
        reporterBunchId: Long,
        targetBunchId: Long,
        reportedByMemberId: Long,
        reason: String,
        detail: String? = null
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_profile_reports
            (reporter_bunch_id, target_bunch_id, reported_by_member_id, reason, detail, created_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        reporterBunchId,
        targetBunchId,
        reportedByMemberId,
        reason,
        detail
    )!!

    private fun columnNames(schema: String, table: String): List<String> = jdbc.query(
        """SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position""",
        { resultSet, _ -> resultSet.getString("column_name") },
        schema,
        table
    )

    private fun indexCount(schema: String, table: String, index: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM pg_indexes
            WHERE schemaname = ? AND tablename = ? AND indexname = ?""",
        Long::class.java,
        schema,
        table,
        index
    )!!

    private fun columnDefinition(schema: String, table: String, column: String): Map<String, Any?> = jdbc.queryForMap(
        """SELECT data_type, character_maximum_length, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
        schema,
        table,
        column
    )

    private companion object {
        const val APPROVED_AT = "2026-11-01T12:00:00Z"
    }
}
