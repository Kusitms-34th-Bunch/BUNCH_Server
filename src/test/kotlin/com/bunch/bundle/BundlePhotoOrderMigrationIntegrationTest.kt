package com.bunch.bundle

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
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
class BundlePhotoOrderMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `migration preserves old positions and retains tombstone until bundle deletion and is repeatable`() {
        val schema = "photo_order_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val version = MigrationVersion.fromVersion("40")
            val previous = configure(schema).info().all().mapNotNull { it.version }.filter { it < version }.max()
            configure(schema, previous.toString()).migrate()
            val member = jdbc.queryForObject(
                "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id", Long::class.java
            )!!
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, "order-${UUID.randomUUID()}", member, UUID.randomUUID()
            )!!
            val bundle = jdbc.queryForObject(
                """INSERT INTO $schema.bundles(bunch_id, created_by, title, revision, created_at, updated_at)
                    VALUES (?, ?, 'unchanged', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunch, member
            )!!
            val asset = jdbc.queryForObject(
                """INSERT INTO $schema.image_assets(public_id, url, created_at)
                    VALUES (?, 'https://images.example/migration.jpg', CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, "order-migration-${UUID.randomUUID()}"
            )!!
            val photo = jdbc.queryForObject(
                """INSERT INTO $schema.bundle_photos(bundle_id, image_asset_id, position, added_by, added_at)
                    VALUES (?, ?, 2, ?, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bundle, asset, member
            )!!
            val current = configure(schema, version.toString())
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT position FROM $schema.bundle_photos WHERE id = ?", Int::class.java, photo
            )).isEqualTo(2)
            assertThat(jdbc.queryForObject(
                "SELECT revision FROM $schema.bundles WHERE id = ?", Long::class.java, bundle
            )).isEqualTo(7)
            jdbc.update(
                """INSERT INTO $schema.bundle_photo_order_marks(bundle_photo_id, bundle_id, deleted,
                    previous_id, next_id, slot) VALUES (?, ?, TRUE, 10001, 10002, 2)""", photo, bundle
            )
            jdbc.update("DELETE FROM $schema.bundle_photos WHERE id = ?", photo)
            assertThat(jdbc.queryForObject(
                "SELECT next_id FROM $schema.bundle_photo_order_marks WHERE bundle_photo_id = ?",
                Long::class.java, photo
            )).isEqualTo(10002)
            jdbc.update("DELETE FROM $schema.bundles WHERE id = ?", bundle)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bundle_photo_order_marks", Long::class.java
            )).isZero()
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
            .cleanDisabled(true)
        if (target != null) configuration.target(target)
        return configuration.load()
    }
}
