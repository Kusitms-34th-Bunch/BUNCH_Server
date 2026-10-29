package com.bunch.bundle

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
class BundleLeaseMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V8 removes lease screen id while applying once and preserving lease data`() {
        withTarget7 { schema ->
            val memberId = insertMember(schema)
            val bunchId = insertBunch(schema, memberId)
            val bundleId = insertBundle(schema, bunchId, memberId)
            val token = UUID.randomUUID()
            jdbc.update(
                """INSERT INTO $schema.bundle_edit_leases
                    (bundle_id, target, member_id, session_id, screen_id, connection_id,
                     token, version, last_heartbeat_at)
                    VALUES (?, 'TITLE', ?, ?, 'legacy-screen', 'legacy-connection', ?, 4, CURRENT_TIMESTAMP)""",
                bundleId,
                memberId,
                UUID.randomUUID().toString(),
                token
            )

            val current = configure(schema, "8")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()

            assertThat(columnCount(schema, "bundle_edit_leases", "screen_id")).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT target FROM $schema.bundle_edit_leases WHERE bundle_id = ?",
                String::class.java,
                bundleId
            )).isEqualTo("TITLE")
            assertThat(jdbc.queryForObject(
                "SELECT member_id FROM $schema.bundle_edit_leases WHERE bundle_id = ?",
                Long::class.java,
                bundleId
            )).isEqualTo(memberId)
            assertThat(jdbc.queryForObject(
                "SELECT connection_id FROM $schema.bundle_edit_leases WHERE bundle_id = ?",
                String::class.java,
                bundleId
            )).isEqualTo("legacy-connection")
            assertThat(jdbc.queryForObject(
                "SELECT token FROM $schema.bundle_edit_leases WHERE bundle_id = ?",
                UUID::class.java,
                bundleId
            )).isEqualTo(token)
            assertThat(jdbc.queryForObject(
                "SELECT version FROM $schema.bundle_edit_leases WHERE bundle_id = ?",
                Long::class.java,
                bundleId
            )).isEqualTo(4)
            assertThat(current.migrate().migrationsExecuted).isZero()
        }
    }

    private fun withTarget7(block: (String) -> Unit) {
        val schema = "bundle_lease_migration_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val legacy = configure(schema, "7")
            assertThat(legacy.migrate().migrationsExecuted).isEqualTo(7)
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

    private fun insertMember(schema: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members (role, nickname)
            VALUES ('ROLE_USER', ?) RETURNING id""",
        Long::class.java,
        "migration-member-${UUID.randomUUID()}"
    )!!

    private fun insertBunch(schema: String, memberId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, 'migration', 'PRIVATE', 'TYPE_2', ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        "migration-bunch-${UUID.randomUUID()}",
        memberId,
        UUID.randomUUID(),
        "migration-fingerprint"
    )!!

    private fun insertBundle(schema: String, bunchId: Long, memberId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bundles (bunch_id, created_by, created_at, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
        Long::class.java,
        bunchId,
        memberId
    )!!

    private fun columnCount(schema: String, table: String, column: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
        Long::class.java,
        schema,
        table,
        column
    )!!
}
