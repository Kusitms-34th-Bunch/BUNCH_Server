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
class BundleNotificationMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V13 preserves participation time and cancels only already written comments without producing alerts`() {
        val schema = "bundle_notification_" + UUID.randomUUID().toString().replace("-", "")
        try {
            configure(schema, "12").migrate()
            val owner = insertMember(schema, "owner")
            val other = insertMember(schema, "other")
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches
                    (name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES (?, 'migration', 'PRIVATE', 'TYPE_1', ?, ?, 'migration', CURRENT_TIMESTAMP)
                    RETURNING id""",
                Long::class.java,
                "migration-${UUID.randomUUID()}", owner, UUID.randomUUID()
            )!!
            val bundle = jdbc.queryForObject(
                """INSERT INTO $schema.bundles (bunch_id, created_by, created_at, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunch, owner
            )!!
            for (member in listOf(owner, other)) {
                jdbc.update(
                    """INSERT INTO $schema.bundle_participants (bundle_id, member_id, joined_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP - INTERVAL '3 days')""", bundle, member
                )
            }
            jdbc.update(
                """INSERT INTO $schema.bundle_comments (bundle_id, member_id, content, created_at, updated_at)
                    VALUES (?, ?, 'written', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""", bundle, owner
            )
            jdbc.update(
                """INSERT INTO $schema.bundle_comments (bundle_id, member_id, content, created_at, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""", bundle, other, "\n\t\u00a0\u202f"
            )
            val joinedAt = jdbc.queryForList("SELECT joined_at FROM $schema.bundle_participants ORDER BY id")
            val migration = configure(schema, "13")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT joined_at FROM $schema.bundle_participants ORDER BY id"))
                .isEqualTo(joinedAt)
            assertThat(jdbc.queryForObject(
                """SELECT reminders_cancelled_at IS NOT NULL AND reminder24_notified_at IS NULL
                    AND reminder72_notified_at IS NULL FROM $schema.bundle_participants WHERE member_id = ?""",
                Boolean::class.java, owner
            )).isTrue()
            assertThat(jdbc.queryForObject(
                """SELECT reminders_cancelled_at IS NULL AND reminder24_notified_at IS NULL
                    AND reminder72_notified_at IS NULL FROM $schema.bundle_participants WHERE member_id = ?""",
                Boolean::class.java, other
            )).isTrue()
            assertThat(jdbc.queryForObject(
                "SELECT ready_notified_at IS NULL FROM $schema.bundles WHERE id = ?", Boolean::class.java, bundle
            )).isTrue()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.notifications", Long::class.java)).isZero()
            assertThat(migration.migrate().migrationsExecuted).isZero()
            migration.validate()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun insertMember(schema: String, nickname: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', ?) RETURNING id""", Long::class.java, nickname
    )!!

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target(target)
        .load()
}
