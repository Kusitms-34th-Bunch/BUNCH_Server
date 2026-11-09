package com.bunch.feed

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
class AdvertisementMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V29 preserves accounts enforces personal hide and catalog constraints and reruns safely`() {
        val schema = "ads_" + UUID.randomUUID().toString().replace("-", "")
        try {
            flyway(schema, "28").migrate()
            val member = jdbc.queryForObject(
                """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
                    VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', '광고 회원') RETURNING id""",
                Long::class.java
            )!!
            val prior = jdbc.queryForList("SELECT * FROM $schema.members")
            val migration = flyway(schema, "29")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.members")).isEqualTo(prior)
            val ad = jdbc.queryForObject(
                """INSERT INTO $schema.feed_advertisements
                    (name, introduction, photo_url, place_provider, place_external_id, place_name, place_address)
                    VALUES ('공방', '소개', 'https://example.com/photo.jpg', 'kakao', 'studio', '공방', '서울')
                    RETURNING id""",
                Long::class.java
            )!!
            val hide = "INSERT INTO $schema.feed_advertisement_hides (member_id, advertisement_id) VALUES (?, ?)"
            jdbc.update(hide, member, ad)
            assertThatThrownBy { jdbc.update(hide, member, ad) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(hide, Long.MAX_VALUE, ad) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update(hide, member, Long.MAX_VALUE) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            listOf("{}", "[1]", "[null]").forEach { tags ->
                assertThatThrownBy {
                    jdbc.update("UPDATE $schema.feed_advertisements SET tags = ?::jsonb WHERE id = ?", tags, ad)
                }.isInstanceOf(DataIntegrityViolationException::class.java)
            }
            listOf(
                "place_provider = 'KAKAO'", "place_external_id = ' '", "place_name = ' '",
                "place_address = ' '", "photo_url = 'javascript:alert(1)'", "external_link = '/relative'"
            ).forEach { change ->
                assertThatThrownBy { jdbc.update("UPDATE $schema.feed_advertisements SET $change WHERE id = ?", ad) }
                    .isInstanceOf(DataIntegrityViolationException::class.java)
            }
            jdbc.update("DELETE FROM $schema.members WHERE id = ?", member)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.feed_advertisement_hides", Long::class.java))
                .isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.feed_advertisements", Long::class.java))
                .isEqualTo(1)
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
