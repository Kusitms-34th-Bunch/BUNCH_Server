package com.bunch.bundle

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
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
class BundleProfanityMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `upgrades preserve legacy and examined data while removing only the recheck index`() {
        val schema = "profanity_" + UUID.randomUUID().toString().replace("-", "")
        try {
            migration(schema, "43").migrate()
            val member = jdbc.queryForObject("INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id",
                Long::class.java)!!
            val bunch = jdbc.queryForObject("""INSERT INTO $schema.bunches(name,introduction,visibility,character_type,
                created_by,creation_request_key,creation_fingerprint,created_at)
                VALUES (?,'migration','PUBLIC','TYPE_2',?,?,'test',now()) RETURNING id""",
                Long::class.java, "migration-${UUID.randomUUID()}", member, UUID.randomUUID())!!
            val bundle = jdbc.queryForObject("""INSERT INTO $schema.bundles(bunch_id,created_by,title,revision,
                title_version,created_at,updated_at) VALUES (?,?,'씨발',7,3,now(),now()) RETURNING id""",
                Long::class.java, bunch, member)!!
            jdbc.update("""INSERT INTO $schema.bundle_comments(bundle_id,member_id,content,version,created_at,updated_at)
                VALUES (?,?,'fuck',5,now(),now())""", bundle, member)
            val upgrade = migration(schema, "46")

            assertThat(upgrade.migrate().migrationsExecuted).isPositive()
            assertThat(upgrade.info().current().version.toString()).isEqualTo("46")
            assertThat(jdbc.queryForMap("""SELECT title,revision,title_version,profanity_dictionary_version,
                title_has_prohibited_word FROM $schema.bundles WHERE id=?""", bundle))
                .containsEntry("title", "씨발").containsEntry("revision", 7L).containsEntry("title_version", 3L)
                .containsEntry("profanity_dictionary_version", null).containsEntry("title_has_prohibited_word", false)
            assertThat(jdbc.queryForMap("SELECT content,version,has_prohibited_word FROM $schema.bundle_comments"))
                .containsEntry("content", "fuck").containsEntry("version", 5L).containsEntry("has_prohibited_word", false)
            upgrade.validate()
            assertThat(upgrade.migrate().migrationsExecuted).isZero()

            val examined = jdbc.queryForObject("""INSERT INTO $schema.bundles(bunch_id,created_by,title,revision,
                title_version,profanity_dictionary_version,title_has_prohibited_word,created_at,updated_at)
                VALUES (?,?,'examined title',11,8,?,true,now(),now()) RETURNING id""",
                Long::class.java, bunch, member, "a".repeat(64))!!
            jdbc.update("""INSERT INTO $schema.bundle_comments(bundle_id,member_id,content,version,
                has_prohibited_word,created_at,updated_at) VALUES (?,?,'examined comment',9,true,now(),now())""",
                examined, member)
            val bundleQuery = """SELECT id,title,revision,title_version,profanity_dictionary_version,
                title_has_prohibited_word,created_at,updated_at FROM $schema.bundles ORDER BY id"""
            val commentQuery = """SELECT bundle_id,member_id,content,version,has_prohibited_word,created_at,updated_at
                FROM $schema.bundle_comments ORDER BY bundle_id,member_id"""
            val bundlesBefore = jdbc.queryForList(bundleQuery)
            val commentsBefore = jdbc.queryForList(commentQuery)
            val indexQuery = """SELECT count(*) FROM pg_indexes
                WHERE schemaname=? AND indexname='ix_bundles_draft_profanity_recheck'"""
            assertThat(jdbc.queryForObject(indexQuery, Long::class.java, schema)).isEqualTo(1L)

            val removal = migration(schema, "47")
            assertThat(removal.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(removal.info().current().version.toString()).isEqualTo("47")
            assertThat(jdbc.queryForObject(indexQuery, Long::class.java, schema)).isZero()
            assertThat(jdbc.queryForList(bundleQuery)).isEqualTo(bundlesBefore)
            assertThat(jdbc.queryForList(commentQuery)).isEqualTo(commentsBefore)
            removal.validate()
            assertThat(removal.migrate().migrationsExecuted).isZero()
            assertThat(jdbc.queryForList(bundleQuery)).isEqualTo(bundlesBefore)
            assertThat(jdbc.queryForList(commentQuery)).isEqualTo(commentsBefore)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun migration(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
