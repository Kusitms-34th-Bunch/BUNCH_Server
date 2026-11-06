package com.bunch.bundle

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
class BundleLocationMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V27 preserves existing bundles and leases while adding bounded unique locations and LOCATION lease`() {
        val schema = "bundle_location_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val previousVersion = configure(schema).info().all().mapNotNull { it.version }
                .filter { it < MigrationVersion.fromVersion("27") }.max()
            configure(schema, previousVersion.toString()).migrate()
            val memberId = jdbc.queryForObject(
                "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id", Long::class.java
            )!!
            val bunchId = jdbc.queryForObject(
                """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, "migration-${UUID.randomUUID()}", memberId, UUID.randomUUID()
            )!!
            val bundleId = jdbc.queryForObject(
                """INSERT INTO $schema.bundles(bunch_id, created_by, title, revision, created_at, updated_at)
                    VALUES (?, ?, 'existing title', 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunchId, memberId
            )!!
            insertLease(schema, bundleId, memberId, "TITLE")
            val current = configure(schema, "27")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForObject("SELECT location_version FROM $schema.bundles WHERE id = ?",
                Long::class.java, bundleId)).isZero()
            assertThat(jdbc.queryForObject("SELECT title FROM $schema.bundles WHERE id = ?",
                String::class.java, bundleId)).isEqualTo("existing title")
            assertThat(jdbc.queryForObject("SELECT revision FROM $schema.bundles WHERE id = ?",
                Long::class.java, bundleId)).isEqualTo(5)
            assertThat(jdbc.queryForObject("SELECT version FROM $schema.bundle_edit_leases WHERE bundle_id = ?",
                Long::class.java, bundleId)).isEqualTo(4)
            insertLease(schema, bundleId, memberId, "LOCATION")
            insertLease(schema, bundleId, memberId, "PHOTO:1")
            assertThatThrownBy { insertLease(schema, bundleId, memberId, "UNKNOWN") }
                .isInstanceOf(DataIntegrityViolationException::class.java)

            (0..2).forEach { insertLocation(schema, bundleId, "kakao", "id-$it", it) }
            assertThatThrownBy { insertLocation(schema, bundleId, "kakao", "fourth", 3) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insertLocation(schema, bundleId, "kakao", "id-0", 1) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insertLocation(schema, bundleId, "kakao", "new", 0) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insertLocation(schema, bundleId, "BAD", "new", 0) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            jdbc.update("DELETE FROM $schema.bundles WHERE id = ?", bundleId)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.bundle_locations", Long::class.java)).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun insertLocation(schema: String, bundleId: Long, provider: String, id: String, position: Int) {
        jdbc.update("""INSERT INTO $schema.bundle_locations(bundle_id, provider, external_id, name, address, position)
            VALUES (?, ?, ?, 'place', 'address', ?)""", bundleId, provider, id, position)
    }

    private fun insertLease(schema: String, bundleId: Long, memberId: Long, target: String) {
        jdbc.update("""INSERT INTO $schema.bundle_edit_leases(bundle_id, target, member_id, session_id,
            connection_id, token, version, last_heartbeat_at)
            VALUES (?, ?, ?, ?, 'migration-connection', ?, 4, CURRENT_TIMESTAMP)""",
            bundleId, target, memberId, UUID.randomUUID().toString(), UUID.randomUUID())
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
}
