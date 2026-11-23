package com.bunch.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
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
class R2ImageMigrationIntegrationTest @Autowired constructor(private val source: DataSource) {
    private companion object {
        const val PRE_R2_VERSION = "53"
        const val R2_VERSION = "55"
    }

    private val jdbc = JdbcTemplate(source)

    @Test
    fun `empty database migrates directly and repeat validates`() = schema { name ->
        val current = flyway(name, R2_VERSION)
        current.migrate()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM $name.image_assets", Long::class.java)).isZero()
        assertThat(columns(name)).contains("object_key").doesNotContain("url", "public_id")
        val versions = jdbc.queryForList(
            "SELECT version FROM $name.flyway_schema_history WHERE success ORDER BY installed_rank", String::class.java
        )
        assertThat(versions).contains(PRE_R2_VERSION, R2_VERSION).doesNotContain("54")
        current.validate()
        assertThat(current.migrate().migrationsExecuted).isZero()
    }

    @Test
    fun `existing assets require verified mapping and migration failure preserves legacy schema`() = schema { name ->
        flyway(name, PRE_R2_VERSION).migrate()
        jdbc.update("INSERT INTO $name.image_assets(public_id, url, created_at) VALUES ('photos/old', 'https://legacy.example/photo.jpg', CURRENT_TIMESTAMP)")
        assertThatThrownBy { flyway(name, R2_VERSION).migrate() }.isInstanceOf(FlywayException::class.java)
        assertThat(columns(name)).contains("url", "public_id")
        assertThat(jdbc.queryForObject("SELECT public_id FROM $name.image_assets", String::class.java)).isEqualTo("photos/old")
    }

    @Test
    fun `invalid URL mapping cannot remove the legacy columns`() = schema { name ->
        flyway(name, PRE_R2_VERSION).migrate()
        jdbc.execute("ALTER TABLE $name.image_assets ADD COLUMN object_key TEXT")
        jdbc.update("""INSERT INTO $name.image_assets(public_id, url, created_at, object_key)
            VALUES ('photos/old', 'https://legacy.example/photo.jpg', CURRENT_TIMESTAMP, 'https://wrong.example/photo.jpg')""")
        assertThatThrownBy { flyway(name, R2_VERSION).migrate() }.isInstanceOf(FlywayException::class.java)
        assertThat(columns(name)).contains("url", "public_id", "object_key")
    }

    @Test
    fun `prepared mapping preserves asset IDs and restrictive references`() = schema { name ->
        flyway(name, PRE_R2_VERSION).migrate()
        jdbc.execute("ALTER TABLE $name.image_assets ADD COLUMN object_key TEXT")
        val notificationHistory = jdbc.queryForMap(
            "SELECT version, script, checksum, installed_rank FROM $name.flyway_schema_history WHERE version = ?",
            PRE_R2_VERSION
        )
        val id = jdbc.queryForObject("""INSERT INTO $name.image_assets(public_id, url, created_at, object_key)
            VALUES ('photos/old', 'https://legacy.example/photo.jpg', CURRENT_TIMESTAMP, 'photos/42/original.jpg') RETURNING id""", Long::class.java)!!
        jdbc.execute("CREATE TABLE $name.retained (image_asset_id BIGINT REFERENCES $name.image_assets(id))")
        jdbc.update("INSERT INTO $name.retained VALUES (?)", id)
        flyway(name, R2_VERSION).migrate()
        assertThat(jdbc.queryForObject("SELECT object_key FROM $name.image_assets WHERE id = ?", String::class.java, id))
            .isEqualTo("photos/42/original.jpg")
        assertThat(jdbc.queryForObject("SELECT image_asset_id FROM $name.retained", Long::class.java)).isEqualTo(id)
        assertThatThrownBy { jdbc.update("DELETE FROM $name.image_assets WHERE id = ?", id) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        assertThat(columns(name)).doesNotContain("url", "public_id")
        assertThat(jdbc.queryForMap(
            "SELECT version, script, checksum, installed_rank FROM $name.flyway_schema_history WHERE version = ?",
            PRE_R2_VERSION
        )).isEqualTo(notificationHistory)
    }

    @Test
    fun `published only image cannot cut over to an archive key`() = schema { name ->
        flyway(name, PRE_R2_VERSION).migrate()
        jdbc.execute("ALTER TABLE $name.image_assets ADD COLUMN object_key TEXT")
        val member = jdbc.queryForObject("""INSERT INTO $name.members(role,signup_status,account_status)
            VALUES ('ROLE_USER','COMPLETED','ACTIVE') RETURNING id""", Long::class.java)!!
        val bunch = jdbc.queryForObject("""INSERT INTO $name.bunches(name,introduction,visibility,character_type,
            created_by,creation_request_key,creation_fingerprint,created_at)
            VALUES ('r2 guard','guard','PUBLIC','TYPE_1',?,?,'hash',now()) RETURNING id""", Long::class.java,
            member, UUID.randomUUID())!!
        val bundle = jdbc.queryForObject("""INSERT INTO $name.bundles(bunch_id,created_by,status,title,created_at,updated_at)
            VALUES (?,?,'DRAFT','retained',now(),now()) RETURNING id""", Long::class.java, bunch, member)!!
        val asset = jdbc.queryForObject("""INSERT INTO $name.image_assets(public_id,url,created_at,object_key)
            VALUES ('photos/retained','https://legacy.example/p.jpg',now(),?) RETURNING id""", Long::class.java,
            "archive/assets/42/" + "a".repeat(64) + "/original.jpg")!!
        jdbc.update("""INSERT INTO $name.bundle_photos(bundle_id,image_asset_id,position,added_by,added_at)
            VALUES (?,?,0,?,now())""", bundle, asset, member)
        assertThatThrownBy { flyway(name, R2_VERSION).migrate() }.isInstanceOf(FlywayException::class.java)
        assertThat(columns(name)).contains("url", "public_id")
        jdbc.update("UPDATE $name.image_assets SET object_key='photos/42/original.jpg' WHERE id=?", asset)
        flyway(name, R2_VERSION).migrate()
        assertThat(jdbc.queryForObject("SELECT image_asset_id FROM $name.bundle_photos", Long::class.java)).isEqualTo(asset)
    }

    private fun columns(name: String) = jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'image_assets'",
        String::class.java, name
    )

    private fun flyway(name: String, version: String): Flyway = Flyway.configure().dataSource(source)
        .schemas(name).defaultSchema(name).locations("classpath:db/migration").target(version).load()

    private fun schema(block: (String) -> Unit) {
        val name = "r2_migration_" + UUID.randomUUID().toString().replace("-", "")
        try {
            block(name)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $name CASCADE")
        }
    }
}
