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

/** The columns of the AI analysis and of the quality that the photo features got (recommended bundle spec 4-3, 4-5). */
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PhotoRecoFeatureQualityMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `a feature row from before keeps no quality and no analysis, and the migration can be repeated`() {
        val schema = "photo_quality_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val version = MigrationVersion.fromVersion("48")
            val previous = configure(schema).info().all().mapNotNull { it.version }.filter { it < version }.max()
            configure(schema, previous.toString()).migrate()
            val photo = insertOldPhoto(schema)
            jdbc.update("INSERT INTO $schema.photo_reco_feature (photo_id, source) VALUES (?, 'CAMERA')", photo)

            val current = configure(schema, version.toString())
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()

            val row = jdbc.queryForMap(
                """SELECT source, ai_status, ai_model_version, ai_raw, quality, analyzed_at
                    FROM $schema.photo_reco_feature WHERE photo_id = ?""",
                photo
            )
            assertThat(row["source"]).isEqualTo("CAMERA")
            assertThat(row["ai_status"]).isNull()
            assertThat(row["ai_model_version"]).isNull()
            assertThat(row["ai_raw"]).isNull()
            assertThat(row["quality"]).isNull()
            assertThat(row["analyzed_at"]).isNull()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `the states and the grades are kept to their values and go with the photo`() {
        val schema = "photo_quality_" + UUID.randomUUID().toString().replace("-", "")
        try {
            configure(schema).migrate()
            val photo = insertOldPhoto(schema)
            jdbc.update("INSERT INTO $schema.photo_reco_feature (photo_id) VALUES (?)", photo)

            jdbc.update(
                """UPDATE $schema.photo_reco_feature SET ai_status = 'DONE', ai_model_version = 'model-1',
                    ai_raw = CAST('{"activity":{"code":"MEAL","conf":0.9}}' AS jsonb), quality = 'GOOD',
                    analyzed_at = CURRENT_TIMESTAMP WHERE photo_id = ?""",
                photo
            )
            listOf("PENDING", "DONE", "FAILED").forEach {
                jdbc.update("UPDATE $schema.photo_reco_feature SET ai_status = ? WHERE photo_id = ?", it, photo)
            }
            listOf("GOOD", "NORMAL", "LOW").forEach {
                jdbc.update("UPDATE $schema.photo_reco_feature SET quality = ? WHERE photo_id = ?", it, photo)
            }

            assertThatThrownBy {
                jdbc.update("UPDATE $schema.photo_reco_feature SET ai_status = 'MAYBE' WHERE photo_id = ?", photo)
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy {
                jdbc.update("UPDATE $schema.photo_reco_feature SET quality = 'BAD' WHERE photo_id = ?", photo)
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            jdbc.update("DELETE FROM $schema.photos WHERE id = ?", photo)
            assertThat(
                jdbc.queryForObject("SELECT count(*) FROM $schema.photo_reco_feature", Long::class.java)
            ).isZero()
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
            "quality-${UUID.randomUUID()}",
            member,
            UUID.randomUUID()
        )!!
        val asset = jdbc.queryForObject(
            """INSERT INTO $schema.image_assets(public_id, url, created_at)
                VALUES (?, 'https://images.example/migration.jpg', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            "quality-migration-${UUID.randomUUID()}"
        )!!
        return jdbc.queryForObject(
            """INSERT INTO $schema.photos(bunch_id, image_asset_id, file_hash, processing_status, date_source,
                created_at)
                VALUES (?, ?, ?, 'READY', 'UNKNOWN', CURRENT_TIMESTAMP) RETURNING id""",
            Long::class.java,
            bunch,
            asset,
            "b".repeat(64)
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
