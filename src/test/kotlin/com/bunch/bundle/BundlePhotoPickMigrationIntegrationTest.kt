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
class BundlePhotoPickMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `migration preserves captions and constrains pick ownership with cascade cleanup and repeatable validation`() {
        val schema = "bundle_pick_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val version = "38"
            configure(schema, "32").migrate()
            val member = id("INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id")
            val other = id("INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id")
            val bunch = id("""INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                creation_request_key, creation_fingerprint, created_at)
                VALUES (?, 'pick', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
                "pick-${UUID.randomUUID()}", member, UUID.randomUUID())
            fun bundle() = id("""INSERT INTO $schema.bundles(bunch_id, created_by, revision, created_at, updated_at)
                VALUES (?, ?, 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""", bunch, member)
            val first = bundle()
            val second = bundle()
            val image = id("""INSERT INTO $schema.image_assets(public_id, url, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP) RETURNING id""",
                "migration/${UUID.randomUUID()}", "https://images.example/pick.jpg")
            val photo = id("""INSERT INTO $schema.bundle_photos(bundle_id, image_asset_id, position, added_by,
                added_at, caption, caption_version) VALUES (?, ?, 0, ?, CURRENT_TIMESTAMP, '과거 캡션', 3) RETURNING id""",
                first, image, member)
            jdbc.update("""INSERT INTO $schema.bundle_participants(bundle_id, member_id, joined_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)""",
                first, member)
            jdbc.update("""INSERT INTO $schema.bundle_participants(bundle_id, member_id, joined_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)""",
                second, other)
            val migration = configure(schema, version)
            migration.migrate()
            assertThat(migration.info().current()?.version).isEqualTo(MigrationVersion.fromVersion(version))
            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForObject(
                "SELECT caption FROM $schema.bundle_photos WHERE id = ?", String::class.java, photo
            ))
                .isEqualTo("과거 캡션")
            assertThat(jdbc.queryForObject(
                "SELECT caption_version FROM $schema.bundle_photos WHERE id = ?", Long::class.java, photo
            ))
                .isEqualTo(3)
            assertThat(jdbc.queryForObject(
                "SELECT revision FROM $schema.bundles WHERE id = ?", Long::class.java, first
            ))
                .isEqualTo(7)
            fun insert(bundle: Long, memberId: Long, version: Long = 8) = jdbc.update(
                """INSERT INTO $schema.bundle_photo_picks
                (bundle_id, bundle_photo_id, member_id, picked, version) VALUES (?, ?, ?, true, ?)""",
                bundle, photo, memberId, version)
            assertThatThrownBy { insert(first, other) }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insert(second, other) }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insert(first, member, -1) }.isInstanceOf(DataIntegrityViolationException::class.java)
            insert(first, member)
            val initialVersion = "39"
            val initialVersions = configure(schema, initialVersion)
            initialVersions.migrate()
            assertThat(initialVersions.info().current()?.version)
                .isEqualTo(MigrationVersion.fromVersion(initialVersion))
            initialVersions.validate()
            assertThat(initialVersions.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForList(
                "SELECT pick_initial_version FROM $schema.bundle_participants", Long::class.java
            )).containsOnly(0L)
            assertThat(jdbc.queryForObject(
                "SELECT version FROM $schema.bundle_photo_picks WHERE bundle_photo_id = ?",
                Long::class.java, photo
            )).isEqualTo(8)
            assertThat(jdbc.queryForObject(
                "SELECT caption FROM $schema.bundle_photos WHERE id = ?", String::class.java, photo
            )).isEqualTo("과거 캡션")
            assertThatThrownBy {
                jdbc.update("UPDATE $schema.bundle_participants SET pick_initial_version = -1")
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy {
                jdbc.update("UPDATE $schema.bundle_participants SET pick_initial_version = NULL")
            }.isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { insert(first, member) }.isInstanceOf(DataIntegrityViolationException::class.java)
            jdbc.update("DELETE FROM $schema.bundle_participants WHERE bundle_id = ?", first)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bundle_photo_picks", Long::class.java
            )).isZero()
            jdbc.update("""INSERT INTO $schema.bundle_participants(bundle_id, member_id, joined_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)""",
                first, member)
            assertThat(jdbc.queryForObject(
                "SELECT pick_initial_version FROM $schema.bundle_participants WHERE bundle_id = ?",
                Long::class.java, first
            )).isZero()
            insert(first, member)
            jdbc.update("DELETE FROM $schema.bundle_photos WHERE id = ?", photo)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bundle_photo_picks", Long::class.java
            )).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun id(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
