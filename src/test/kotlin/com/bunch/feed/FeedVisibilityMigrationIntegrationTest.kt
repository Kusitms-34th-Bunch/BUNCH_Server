package com.bunch.feed

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataIntegrityViolationException
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
class FeedVisibilityMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V22 to V23 preserves data validates constraints and safely reruns`() {
        val schema = "feed_visibility_" + UUID.randomUUID().toString().replace("-", "")
        try {
            assertThat(flyway(schema, "22").migrate().migrationsExecuted).isEqualTo(22)
            val member = jdbc.queryForObject(
                """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
                    VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', 'migration owner') RETURNING id""",
                Long::class.java
            )!!
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches
                    (name, introduction, visibility, character_type, created_by,
                     creation_request_key, creation_fingerprint, created_at)
                    VALUES ('migration bunch', 'intro', 'PUBLIC', 'TYPE_2', ?, ?, 'fingerprint', now()) RETURNING id""",
                Long::class.java,
                member,
                UUID.randomUUID()
            )!!
            val priorMembers = jdbc.queryForList("SELECT * FROM $schema.members")
            val priorBunches = jdbc.queryForList("SELECT * FROM $schema.bunches")
            val migration = flyway(schema, "23")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.members")).isEqualTo(priorMembers)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.bunches")).isEqualTo(priorBunches)

            val insert = """INSERT INTO $schema.feed_content_hides (target_kind, target_id, reporter_bunch_id, reason)
                VALUES (?, ?, ?, ?)"""
            jdbc.update(insert, "COMMENT", 99, null, "operator review")
            jdbc.update(insert, "COMMENT", 99, bunch, "REPORTED")
            jdbc.update(insert, "BUNDLE", 99, bunch, "REPORTED")
            assertThatThrownBy { jdbc.update(insert, "COMMENT", 99, null, "duplicate operator") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, "COMMENT", 99, bunch, "duplicate reporter") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, "PHOTO", 99, null, "invalid kind") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, "BUNDLE", 0, null, "invalid id") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, "BUNDLE", 100, null, " ") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, "BUNDLE", 100, Long.MAX_VALUE, "missing reporter") }
                .isInstanceOf(DataIntegrityViolationException::class.java)

            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", bunch)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.feed_content_hides", Long::class.java))
                .isEqualTo(1)
            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun flyway(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
