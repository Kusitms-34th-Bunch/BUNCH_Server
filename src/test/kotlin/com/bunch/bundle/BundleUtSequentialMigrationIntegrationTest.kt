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
class BundleUtSequentialMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `each PR upgrades an already migrated database without skipping changes or altering old content`() {
        val schema = "ut_stack_" + UUID.randomUUID().toString().replace("-", "")
        try {
            migration(schema, "35").migrate()
            val member = jdbc.queryForObject(
                "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id", Long::class.java
            )!!
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, "stack-${UUID.randomUUID()}", member, UUID.randomUUID()
            )!!
            val bundle = jdbc.queryForObject(
                """INSERT INTO $schema.bundles(bunch_id, created_by, title, revision, created_at, updated_at)
                    VALUES (?, ?, 'existing title', 7, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunch, member
            )!!
            jdbc.update(
                "INSERT INTO $schema.bundle_participants(bundle_id, member_id, joined_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                bundle, member
            )

            val versions = listOf("1260", "1270", "1280", "1281", "1290", "1291", "1292")
                .map { "202611111$it" }
            for (version in versions) {
                val step = migration(schema, version)
                assertThat(step.migrate().migrationsExecuted).describedAs("apply %s", version).isEqualTo(1)
                assertThat(step.info().current().version.toString()).isEqualTo(version)
                step.validate()
                assertThat(step.migrate().migrationsExecuted).isZero()
                assertThat(jdbc.queryForMap("SELECT title, revision FROM $schema.bundles WHERE id = ?", bundle))
                    .containsEntry("title", "existing title").containsEntry("revision", 7L)
            }
            assertThat(jdbc.queryForObject(
                "SELECT pick_initial_version FROM $schema.bundle_participants WHERE bundle_id = ?",
                Long::class.java, bundle
            )).isZero()
            assertThat(jdbc.queryForMap(
                "SELECT title_crdt, published_by FROM $schema.bundles WHERE id = ?", bundle
            )).containsEntry("title_crdt", null).containsEntry("published_by", null)
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
