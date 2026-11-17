package com.bunch.member

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
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
class MemberConsentHistoryMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `old consents stay as agreements, the history is allowed only for the optional one, and it can be repeated`() {
        val schema = "consent_history_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val version = MigrationVersion.fromVersion("45")
            val previous = configure(schema).info().all().mapNotNull { it.version }.filter { it < version }.max()
            configure(schema, previous.toString()).migrate()
            val member = insertMember(schema)
            insertOld(schema, member, "SERVICE")
            insertOld(schema, member, "DEMOGRAPHICS")

            val current = configure(schema, version.toString())
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()

            assertThat(
                jdbc.queryForList(
                    "SELECT agreed FROM $schema.member_consents WHERE member_id = ? ORDER BY id",
                    Boolean::class.java,
                    member
                )
            ).containsExactly(true, true)

            // The optional consent keeps a history, also of the same version.
            insert(schema, member, "DEMOGRAPHICS", "v1", false)
            insert(schema, member, "DEMOGRAPHICS", "v1", true)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM $schema.member_consents WHERE member_id = ? AND type = 'DEMOGRAPHICS'",
                    Long::class.java,
                    member
                )
            ).isEqualTo(3)

            // A required term is still agreed to once per version.
            assertThatThrownBy { insert(schema, member, "SERVICE", "v1", true) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            insert(schema, member, "SERVICE", "v2", true)

            // A new row must say whether it agrees.
            assertThatThrownBy {
                jdbc.update(
                    "INSERT INTO $schema.member_consents(member_id, type, version, agreed_at) " +
                        "VALUES (?, 'PRIVACY', 'v1', now())",
                    member
                )
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun insertMember(schema: String): Long = jdbc.queryForObject(
        "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id",
        Long::class.java
    )!!

    // A row as it was written before the migration: it has no agreed column.
    private fun insertOld(schema: String, member: Long, type: String) {
        jdbc.update(
            "INSERT INTO $schema.member_consents(member_id, type, version, agreed_at) VALUES (?, ?, 'v1', now())",
            member,
            type
        )
    }

    private fun insert(schema: String, member: Long, type: String, version: String, agreed: Boolean) {
        jdbc.update(
            "INSERT INTO $schema.member_consents(member_id, type, version, agreed, agreed_at) " +
                "VALUES (?, ?, ?, ?, now())",
            member,
            type,
            version,
            agreed
        )
    }

    private fun configure(schema: String, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(true)
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}
