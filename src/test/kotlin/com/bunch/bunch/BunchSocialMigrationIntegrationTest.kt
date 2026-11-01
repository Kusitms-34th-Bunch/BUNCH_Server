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
    fun `bunch follows migration enforces relationship constraints and indexes`() {
        withTarget10 { schema ->
            val creator = insertMember(schema, "social-creator")
            val approver = insertMember(schema, "social-approver")
            val actor = insertMember(schema, "social-actor")
            val newBunch = { suffix: String -> insertBunch(schema, "social-$suffix", creator) }

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

            val migration = configure(schema, "13")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
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
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", followerCascadeFollower)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_follows WHERE id = ?",
                Long::class.java,
                followerCascadeId
            )).isZero()
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", followedCascadeFollowed)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_follows WHERE id = ?",
                Long::class.java,
                followedCascadeId
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
            configure(schema, "10").migrate()
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

    private companion object {
        const val APPROVED_AT = "2026-11-01T12:00:00Z"
    }
}
