package com.bunch.place

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
class PinnedPlaceMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V28 migrates existing places once enforces uniqueness and preserves source deletion snapshots`() {
        val schema = "pin_" + UUID.randomUUID().toString().replace("-", "")
        try {
            configure(schema, "27").migrate()
            val member = jdbc.queryForObject(
                "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id", Long::class.java
            )!!
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, "migration-${UUID.randomUUID()}", member, UUID.randomUUID()
            )!!
            val bundle = jdbc.queryForObject(
                """INSERT INTO $schema.bundles(bunch_id, created_by, title, created_at, updated_at)
                    VALUES (?, ?, 'existing', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunch, member
            )!!
            val location = jdbc.queryForObject(
                """INSERT INTO $schema.bundle_locations(bundle_id, provider, external_id, name, address, position)
                    VALUES (?, 'kakao', 'external', 'original', 'address', 0) RETURNING id""",
                Long::class.java, bundle
            )!!
            val migration = configure(schema, "28")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForObject("SELECT name FROM $schema.bundle_locations WHERE id = ?",
                String::class.java, location)).isEqualTo("original")
            fun insert(provider: String = "kakao", external: String = "external", locationId: Long = location) {
                jdbc.update("""INSERT INTO $schema.pinned_places(bunch_id, provider, external_id, name, address,
                    source_type, source_bundle_id, source_location_id, pinned_by_member_id, pinned_at)
                    VALUES (?, ?, ?, 'original', 'address', 'BUNDLE', ?, ?, ?, CURRENT_TIMESTAMP)""",
                    bunch, provider, external, bundle, locationId, member)
            }
            insert()
            assertThatThrownBy { insert() }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insert("BAD") }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insert(external = " ") }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insert(external = "no-source", locationId = Long.MAX_VALUE) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            insert("google")
            jdbc.update("DELETE FROM $schema.bundles WHERE id = ?", bundle)
            assertThat(jdbc.queryForObject("""SELECT count(*) FROM $schema.pinned_places
                WHERE source_bundle_id IS NULL AND source_location_id IS NULL
                    AND name = 'original' AND address = 'address' AND pinned_by_member_id = ?""",
                Long::class.java, member)).isEqualTo(2)
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", bunch)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.pinned_places", Long::class.java)).isZero()
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
}
