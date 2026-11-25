package com.bunch.photo

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
class PhotoMillisModelMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `an old photo keeps no milliseconds and no device model, and the migration can be repeated`() {
        val schema = "photo_millis_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val version = MigrationVersion.fromVersion("57")
            val previous = configure(schema).info().all().mapNotNull { it.version }.filter { it < version }.max()
            configure(schema, previous.toString()).migrate()
            val photo = insertOldPhoto(schema)

            val current = configure(schema, version.toString())
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()

            assertThat(
                jdbc.queryForMap("SELECT captured_millis FROM $schema.photos WHERE id = ?", photo)["captured_millis"]
            ).isNull()
            // The size of the image (width, height) comes with V56__photo_dimensions.sql and is tested there.
            jdbc.update("INSERT INTO $schema.photo_reco_feature (photo_id, source) VALUES (?, 'CAMERA')", photo)
            assertThat(
                jdbc.queryForList(
                    "SELECT device_model FROM $schema.photo_reco_feature WHERE photo_id = ?",
                    String::class.java,
                    photo
                ).single()
            ).isNull()
            // The old photo has no capture time, so the database refuses milliseconds for it.
            assertThatThrownBy {
                jdbc.update("UPDATE $schema.photos SET captured_millis = 5 WHERE id = ?", photo)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun insertOldPhoto(schema: String): Long {
        val member = jdbc.queryForObject(
            "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id",
            Long::class.java
        )!!
        val bunch = jdbc.queryForObject(
            """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                creation_request_key, creation_fingerprint, created_at)
                VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            "millis-${UUID.randomUUID()}",
            member,
            UUID.randomUUID()
        )!!
        val asset = jdbc.queryForObject(
            """INSERT INTO $schema.image_assets(object_key, created_at)
                VALUES (?, CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            "photos/millis-${UUID.randomUUID()}/original.jpg"
        )!!
        return jdbc.queryForObject(
            """INSERT INTO $schema.photos(bunch_id, image_asset_id, file_hash, processing_status, date_source,
                created_at)
                VALUES (?, ?, ?, 'READY', 'UNKNOWN', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            bunch,
            asset,
            "a".repeat(64)
        )!!
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
