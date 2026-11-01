package com.bunch.bunch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
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
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BunchRoleMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V11 preserves legacy data initializes versions and backfills only inactive end times`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema)
            val bunchId = insertBunch(schema, ownerId)
            insertMembership(schema, bunchId, ownerId, "OWNER")
            insertMembership(schema, bunchId, insertMember(schema), "MEMBER")
            insertMembership(schema, bunchId, ownerId, "OWNER", "LEFT")
            insertMembership(schema, bunchId, insertMember(schema), "MEMBER", "REMOVED")
            val originalBunch = jdbc.queryForMap("SELECT * FROM $schema.bunches WHERE id = ?", bunchId)
            val originalMemberships = jdbc.queryForList("SELECT * FROM $schema.bunch_memberships ORDER BY id")
            val originalHistory = jdbc.queryForList(
                "SELECT * FROM $schema.flyway_schema_history ORDER BY installed_rank"
            )
            val startedAt = databaseNow()

            val current = configure(schema, "11")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            val completedAt = databaseNow()
            current.validate()

            assertThat(jdbc.queryForMap("SELECT * FROM $schema.bunches WHERE id = ?", bunchId))
                .containsAllEntriesOf(originalBunch)
                .containsEntry("profile_version", 0L)
                .containsEntry("membership_version", 0L)
            val migrated = jdbc.queryForList("SELECT * FROM $schema.bunch_memberships ORDER BY id")
            assertThat(migrated).hasSameSizeAs(originalMemberships)
            migrated.zip(originalMemberships).forEach { (actual, original) ->
                assertThat(actual).containsAllEntriesOf(original)
                assertThat(actual["role_granted_by"]).isNull()
                assertThat(actual["last_activity_at"]).isNull()
                if (actual["status"] == "ACTIVE") {
                    assertThat(actual["ended_at"]).isNull()
                } else {
                    assertThat((actual["ended_at"] as Timestamp).toInstant()).isBetween(startedAt, completedAt)
                }
            }
            assertThat(jdbc.queryForList(
                "SELECT * FROM $schema.flyway_schema_history WHERE version IS DISTINCT FROM '11' ORDER BY installed_rank"
            )).isEqualTo(originalHistory)
            assertThat(jdbc.queryForObject(
                """SELECT count(DISTINCT ended_at) FROM $schema.bunch_memberships
                    WHERE status IN ('LEFT', 'REMOVED')""",
                Long::class.java
            )).isEqualTo(1L)
            assertThat(current.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForList("SELECT * FROM $schema.bunch_memberships ORDER BY id")).isEqualTo(migrated)
            current.validate()
        }
    }

    @Test
    fun `V11 enforces version types defaults nullability and nonnegative values`() {
        withTarget10 { schema ->
            configure(schema, "11").migrate()
            val bunchId = insertBunch(schema, insertMember(schema))
            listOf("profile_version", "membership_version").forEach { column ->
                assertThat(jdbc.queryForMap(
                    """SELECT data_type, is_nullable, column_default FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'bunches' AND column_name = ?""",
                    schema,
                    column
                )).containsEntry("data_type", "bigint")
                    .containsEntry("is_nullable", "NO")
                    .containsEntry("column_default", "0")
                assertThat(jdbc.queryForObject(
                    "SELECT $column FROM $schema.bunches WHERE id = ?",
                    Long::class.java,
                    bunchId
                )).isZero()
                assertThatThrownBy { jdbc.update("UPDATE $schema.bunches SET $column = -1 WHERE id = ?", bunchId) }
                    .isInstanceOf(DataAccessException::class.java)
                assertThatThrownBy { jdbc.update("UPDATE $schema.bunches SET $column = NULL WHERE id = ?", bunchId) }
                    .isInstanceOf(DataAccessException::class.java)
                jdbc.update("UPDATE $schema.bunches SET $column = 2147483648 WHERE id = ?", bunchId)
            }
            listOf("ended_at", "last_activity_at").forEach { column ->
                assertThat(jdbc.queryForMap(
                    """SELECT data_type, is_nullable FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'bunch_memberships' AND column_name = ?""",
                    schema,
                    column
                )).containsEntry("data_type", "timestamp with time zone").containsEntry("is_nullable", "YES")
            }
        }
    }

    @Test
    fun `V11 allows several co owners and historical owners but rejects duplicate active owners and invalid roles`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema)
            val bunchId = insertBunch(schema, ownerId)
            insertMembership(schema, bunchId, ownerId, "OWNER")
            configure(schema, "11").migrate()

            val coOwnerId = insertMember(schema)
            insertMembership(schema, bunchId, coOwnerId, "CO_OWNER")
            insertMembership(schema, bunchId, insertMember(schema), "CO_OWNER")
            assertThatThrownBy { insertMembership(schema, bunchId, coOwnerId, "MEMBER") }
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageContaining("uk_bunch_active_membership")
            insertMembership(schema, bunchId, ownerId, "OWNER", "LEFT")
            insertMembership(schema, bunchId, ownerId, "OWNER", "REMOVED")
            assertThatThrownBy { insertMembership(schema, bunchId, insertMember(schema), "OWNER") }
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageContaining("uk_bunch_active_owner")
            assertThatThrownBy { insertMembership(schema, bunchId, insertMember(schema), "ADMIN") }
                .isInstanceOf(DataAccessException::class.java)
                .hasMessageContaining("bunch_memberships_role_check")
            val anotherOwnerId = insertMember(schema)
            insertMembership(schema, insertBunch(schema, anotherOwnerId), anotherOwnerId, "OWNER")
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_memberships WHERE bunch_id = ? AND role = 'CO_OWNER'",
                Long::class.java,
                bunchId
            )).isEqualTo(2L)
        }
    }

    @Test
    fun `role grantor references members and becomes null on grantor deletion`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema)
            val bunchId = insertBunch(schema, ownerId)
            val membershipId = insertMembership(schema, bunchId, ownerId, "OWNER")
            configure(schema, "11").migrate()
            val grantorId = insertMember(schema)
            jdbc.update(
                "UPDATE $schema.bunch_memberships SET role_granted_by = ? WHERE id = ?",
                grantorId,
                membershipId
            )
            assertThat(jdbc.queryForObject(
                "SELECT role_granted_by FROM $schema.bunch_memberships WHERE id = ?",
                Long::class.java,
                membershipId
            )).isEqualTo(grantorId)

            jdbc.update("DELETE FROM $schema.members WHERE id = ?", grantorId)
            assertThat(jdbc.queryForObject(
                "SELECT role_granted_by FROM $schema.bunch_memberships WHERE id = ?",
                Long::class.java,
                membershipId
            )).isNull()
            assertThatThrownBy {
                jdbc.update(
                    "UPDATE $schema.bunch_memberships SET role_granted_by = ? WHERE id = ?",
                    grantorId,
                    membershipId
                )
            }.isInstanceOf(DataAccessException::class.java)
        }
    }

    @Test
    fun `concurrent active owner inserts commit exactly one transaction`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema)
            val bunchId = insertBunch(schema, ownerId)
            val ownerMembershipId = insertMembership(schema, bunchId, ownerId, "OWNER")
            val candidates = listOf(insertMember(schema), insertMember(schema))
            configure(schema, "11").migrate()
            // Services own the zero-owner invariant; open a vacancy to exercise the DB index directly.
            jdbc.update("UPDATE $schema.bunch_memberships SET role = 'MEMBER' WHERE id = ?", ownerMembershipId)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = candidates.map { memberId ->
                    executor.submit<String> { insertOwnerTransaction(schema, bunchId, memberId, barrier) }
                }.map { it.get(15, TimeUnit.SECONDS) }
                assertThat(results).containsExactlyInAnyOrder("committed", "23505")
                assertThat(jdbc.queryForObject(
                    """SELECT count(*) FROM $schema.bunch_memberships
                        WHERE bunch_id = ? AND status = 'ACTIVE' AND role = 'OWNER'""",
                    Long::class.java,
                    bunchId
                )).isEqualTo(1L)
            } finally {
                executor.shutdownNow()
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
            }
        }
    }

    @Test
    fun `active memberships without an active owner fail entirely and can be retried after data correction`() {
        withTarget10 { schema ->
            val memberId = insertMember(schema)
            val bunchId = insertBunch(schema, memberId)
            val membershipId = insertMembership(schema, bunchId, memberId, "MEMBER")
            insertMembership(schema, bunchId, insertMember(schema), "OWNER", "LEFT")
            assertMigrationFailsAndRollsBack(schema)

            jdbc.update("UPDATE $schema.bunch_memberships SET role = 'OWNER' WHERE id = ?", membershipId)
            val current = configure(schema, "11")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
        }
    }

    @Test
    fun `multiple active owners fail entirely instead of choosing an owner`() {
        withTarget10 { schema ->
            val memberId = insertMember(schema)
            val bunchId = insertBunch(schema, memberId)
            insertMembership(schema, bunchId, memberId, "OWNER")
            insertMembership(schema, bunchId, insertMember(schema), "OWNER")
            assertMigrationFailsAndRollsBack(schema)
        }
    }

    @Test
    fun `bunch with no memberships fails entirely`() {
        withTarget10 { schema ->
            insertBunch(schema, insertMember(schema))
            assertMigrationFailsAndRollsBack(schema)
        }
    }

    @Test
    fun `bunch with only ended memberships fails entirely even with a historical owner`() {
        withTarget10 { schema ->
            val memberId = insertMember(schema)
            val bunchId = insertBunch(schema, memberId)
            insertMembership(schema, bunchId, memberId, "OWNER", "REMOVED")
            assertMigrationFailsAndRollsBack(schema)
        }
    }

    private fun insertOwnerTransaction(
        schema: String,
        bunchId: Long,
        memberId: Long,
        barrier: CyclicBarrier
    ): String = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL statement_timeout = '10s'") }
            barrier.await(5, TimeUnit.SECONDS)
            connection.prepareStatement(
                """INSERT INTO $schema.bunch_memberships
                    (bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at)
                    VALUES (?, ?, 'OWNER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"""
            ).use { statement ->
                statement.setLong(1, bunchId)
                statement.setLong(2, memberId)
                statement.executeUpdate()
            }
            connection.commit()
            "committed"
        } catch (exception: SQLException) {
            connection.rollback()
            exception.sqlState
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        }
    }

    private fun assertMigrationFailsAndRollsBack(schema: String) {
        // A valid bunch alongside the invalid one must also remain completely untouched.
        val validMember = insertMember(schema)
        val validBunch = insertBunch(schema, validMember)
        insertMembership(schema, validBunch, validMember, "OWNER")
        insertMembership(schema, validBunch, validMember, "MEMBER", "LEFT")
        val originalBunches = jdbc.queryForList("SELECT * FROM $schema.bunches ORDER BY id")
        val originalMemberships = jdbc.queryForList("SELECT * FROM $schema.bunch_memberships ORDER BY id")
        val originalHistory = jdbc.queryForList("SELECT * FROM $schema.flyway_schema_history ORDER BY installed_rank")

        assertThatThrownBy { configure(schema, "11").migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasStackTraceContaining("requires exactly one active OWNER")
        assertThat(jdbc.queryForList("SELECT * FROM $schema.bunches ORDER BY id")).isEqualTo(originalBunches)
        assertThat(jdbc.queryForList("SELECT * FROM $schema.bunch_memberships ORDER BY id"))
            .isEqualTo(originalMemberships)
        assertThat(jdbc.queryForList("SELECT * FROM $schema.flyway_schema_history ORDER BY installed_rank"))
            .isEqualTo(originalHistory)
        assertThat(jdbc.queryForObject(
            """SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND
                ((table_name = 'bunches' AND column_name IN ('profile_version', 'membership_version')) OR
                 (table_name = 'bunch_memberships' AND column_name IN ('ended_at', 'role_granted_by', 'last_activity_at')))""",
            Long::class.java,
            schema
        )).isZero()
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = 'uk_bunch_active_owner'",
            Long::class.java,
            schema
        )).isZero()
        assertThatThrownBy { insertMembership(schema, validBunch, insertMember(schema), "CO_OWNER") }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("bunch_memberships_role_check")
        configure(schema, "10").validate()
    }

    private fun withTarget10(block: (String) -> Unit) {
        val schema = "bunch_role_migration_" + UUID.randomUUID().toString().replace("-", "")
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

    private fun databaseNow(): Instant = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp::class.java)!!
        .toInstant()

    private fun insertMember(schema: String): Long = jdbc.queryForObject(
        "INSERT INTO $schema.members (role, nickname) VALUES ('ROLE_USER', ?) RETURNING id",
        Long::class.java,
        "migration-${UUID.randomUUID()}"
    )!!

    private fun insertBunch(schema: String, memberId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, 'migration', 'PRIVATE', 'TYPE_2', ?, ?, 'fingerprint', CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        "migration-${UUID.randomUUID()}",
        memberId,
        UUID.randomUUID()
    )!!

    private fun insertMembership(
        schema: String,
        bunchId: Long,
        memberId: Long,
        role: String,
        status: String = "ACTIVE"
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_memberships
            (bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at, join_requests)
            VALUES (?, ?, ?, ?, '2025-01-01T00:00:00Z', '2025-01-02T00:00:00Z',
                    '2025-02-01T00:00:00Z', '{"legacy-key":{"outcome":"JOINED"}}'::jsonb)
            RETURNING id""",
        Long::class.java,
        bunchId,
        memberId,
        role,
        status
    )!!
}
