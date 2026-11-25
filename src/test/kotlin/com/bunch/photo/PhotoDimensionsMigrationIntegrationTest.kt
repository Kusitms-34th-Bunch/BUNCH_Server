package com.bunch.photo

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
class PhotoDimensionsMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V56 leaves old dimensions unknown and requires either a positive pair or two nulls`() {
        val schema = "photo_dimensions_" + UUID.randomUUID().toString().replace("-", "")
        try {
            configure(schema, "55").migrate()
            val member = jdbc.queryForObject(
                "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id",
                Long::class.java
            )!!
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES ('dimensions', 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', now()) RETURNING id""",
                Long::class.java,
                member,
                UUID.randomUUID()
            )!!
            val asset = jdbc.queryForObject(
                """INSERT INTO $schema.image_assets(object_key, created_at)
                    VALUES ('photos/dimensions/original.jpg', now()) RETURNING id""",
                Long::class.java
            )!!
            val photo = jdbc.queryForObject(
                """INSERT INTO $schema.photos(bunch_id, image_asset_id, file_hash, processing_status, date_source,
                    created_at) VALUES (?, ?, ?, 'READY', 'UNKNOWN', now()) RETURNING id""",
                Long::class.java,
                bunch,
                asset,
                "a".repeat(64)
            )!!

            val current = configure(schema, "56")
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()
            val old = jdbc.queryForMap("SELECT width, height FROM $schema.photos WHERE id = ?", photo)
            assertThat(old["width"]).isNull()
            assertThat(old["height"]).isNull()
            assertThat(jdbc.queryForList(
                "SELECT version FROM $schema.flyway_schema_history WHERE success", String::class.java
            )).contains("55", "56").doesNotContain("54")

            val update = "UPDATE $schema.photos SET width = ?, height = ? WHERE id = ?"
            jdbc.update(update, 800, 1200, photo)
            val stored = jdbc.queryForMap("SELECT width, height FROM $schema.photos WHERE id = ?", photo)
            assertThat(stored["width"]).isEqualTo(800)
            assertThat(stored["height"]).isEqualTo(1200)
            for ((width, height) in listOf(null to 1200, 800 to null, 0 to 1200, 800 to 0, -1 to 1200, 800 to -1)) {
                assertThatThrownBy { jdbc.update(update, width, height, photo) }
                    .isInstanceOf(DataIntegrityViolationException::class.java)
            }
            jdbc.update(update, null, null, photo)
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
