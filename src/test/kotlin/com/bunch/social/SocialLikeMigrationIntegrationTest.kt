package com.bunch.social

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.flywaydb.core.Flyway
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
class SocialLikeMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V24 preserves published data enforces unique pairs retains inactive history and cascades`() {
        val schema = "social_like_" + UUID.randomUUID().toString().replace("-", "")
        try {
            assertThat(flyway(schema, "23").migrate().migrationsExecuted).isEqualTo(23)
            val member = jdbc.queryForObject(
                """INSERT INTO $schema.members(role,signup_status,account_status,nickname)
                    VALUES ('ROLE_USER','COMPLETED','ACTIVE','migration') RETURNING id""", Long::class.java
            )!!
            val createBunch = { name: String ->
                jdbc.queryForObject(
                    """INSERT INTO $schema.bunches(name,introduction,visibility,character_type,created_by,
                        creation_request_key,creation_fingerprint,created_at)
                        VALUES (?,'intro','PUBLIC','TYPE_2',?,?,'test',now()) RETURNING id""",
                    Long::class.java, name, member, UUID.randomUUID()
                )!!
            }
            val source = createBunch("source")
            val target = createBunch("target")
            val bundle = jdbc.queryForObject(
                """INSERT INTO $schema.bundles(bunch_id,created_by,status,title,visibility,
                    created_at,updated_at,published_at)
                    VALUES (?,?,'PUBLISHED','kept','PUBLIC',now(),now(),now()) RETURNING id""",
                Long::class.java, target, member
            )!!
            val before = jdbc.queryForList("SELECT * FROM $schema.bundles")
            val migration = flyway(schema, "24")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.bundles")).isEqualTo(before)
            val insert = """INSERT INTO $schema.social_bundle_likes(bunch_id,bundle_id,first_liked_at,updated_at)
                VALUES (?,?,now(),now())"""
            jdbc.update(insert, source, bundle)
            val firstTime = jdbc.queryForObject(
                "SELECT first_liked_at FROM $schema.social_bundle_likes", java.sql.Timestamp::class.java
            )
            jdbc.update("UPDATE $schema.social_bundle_likes SET active = false")
            assertThatThrownBy { jdbc.update(insert, source, bundle) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, Long.MAX_VALUE, bundle) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(insert, source, Long.MAX_VALUE) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThat(jdbc.queryForObject(
                "SELECT first_liked_at FROM $schema.social_bundle_likes", java.sql.Timestamp::class.java
            )).isEqualTo(firstTime)
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", source)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.social_bundle_likes", Long::class.java))
                .isZero()
            jdbc.update(insert, target, bundle)
            jdbc.update("DELETE FROM $schema.bundles WHERE id = ?", bundle)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.social_bundle_likes", Long::class.java))
                .isZero()
            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun flyway(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
