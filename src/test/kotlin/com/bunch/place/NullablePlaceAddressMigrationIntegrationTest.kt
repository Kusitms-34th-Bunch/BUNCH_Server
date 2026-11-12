package com.bunch.place

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
class NullablePlaceAddressMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `nullable address migration preserves existing rows and checks allows null independently and reruns safely`() {
        val schema = "nullable_place_" + UUID.randomUUID().toString().replace("-", "")
        val addressColumns = mapOf(
            "bundle_locations" to "address",
            "pinned_places" to "address",
            "feed_advertisements" to "place_address"
        )
        try {
            flyway(schema, "35").migrate()
            val member = jdbc.queryForObject(
                "INSERT INTO $schema.members(role) VALUES ('ROLE_USER') RETURNING id",
                Long::class.java
            )!!
            val bunch = jdbc.queryForObject(
                """INSERT INTO $schema.bunches(name, introduction, visibility, character_type, created_by,
                    creation_request_key, creation_fingerprint, created_at)
                    VALUES (?, 'migration', 'PUBLIC', 'TYPE_2', ?, ?, 'test', CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, "migration-${UUID.randomUUID()}", member, UUID.randomUUID()
            )!!
            val bundle = jdbc.queryForObject(
                """INSERT INTO $schema.bundles(bunch_id, created_by, title, created_at, updated_at)
                    VALUES (?, ?, 'existing', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunch, member
            )!!
            val location = jdbc.queryForObject(
                """INSERT INTO $schema.bundle_locations(bundle_id, provider, external_id, name, address, position)
                    VALUES (?, 'kakao', 'region', '여의도', '기존 번들 주소', 0) RETURNING id""",
                Long::class.java, bundle
            )!!
            jdbc.update(
                """INSERT INTO $schema.pinned_places(bunch_id, provider, external_id, name, address,
                    source_type, source_bundle_id, source_location_id, pinned_by_member_id, pinned_at)
                    VALUES (?, 'kakao', 'region', '여의도', '기존 콕 주소', 'BUNDLE', ?, ?, ?, CURRENT_TIMESTAMP)""",
                bunch, bundle, location, member
            )
            jdbc.update(
                """INSERT INTO $schema.feed_advertisements
                    (name, introduction, photo_url, place_provider, place_external_id, place_name, place_address)
                    VALUES ('광고', '소개', 'https://example.com/photo.jpg', 'kakao', 'region', '여의도', '기존 광고 주소')"""
            )
            val previousRows = addressColumns.keys.associateWith { jdbc.queryForList("SELECT * FROM $schema.$it") }
            val previousChecks = checks(schema)
            addressColumns.forEach { (table, column) ->
                assertThatThrownBy { jdbc.update("UPDATE $schema.$table SET $column = NULL") }
                    .isInstanceOf(DataIntegrityViolationException::class.java)
                    .hasMessageContaining(column)
            }

            val migration = flyway(schema, "36")
            assertThat(migration.migrate().migrationsExecuted).isPositive()
            assertThat(migration.info().current().version.toString()).isEqualTo("36")
            previousRows.forEach { (table, rows) ->
                assertThat(jdbc.queryForList("SELECT * FROM $schema.$table")).isEqualTo(rows)
            }
            assertThat(checks(schema)).isEqualTo(previousChecks)

            addressColumns.forEach { (table, addressColumn) ->
                assertThat(jdbc.update("UPDATE $schema.$table SET $addressColumn = NULL")).isEqualTo(1)
                val nameColumn = if (table == "feed_advertisements") "place_name" else "name"
                assertThat(jdbc.queryForMap("SELECT $nameColumn, $addressColumn FROM $schema.$table"))
                    .containsEntry(nameColumn, "여의도")
                    .containsEntry(addressColumn, null)
                val constraintPrefix = when (table) {
                    "bundle_locations" -> "ck_bundle_location"
                    "pinned_places" -> "ck_pinned_place"
                    else -> "feed_advertisements_place"
                }
                val addressConstraint = if (table == "feed_advertisements") {
                    "${constraintPrefix}_address_check"
                } else {
                    "${constraintPrefix}_address"
                }
                listOf("", "   ").forEach { blank ->
                    assertThatThrownBy { jdbc.update("UPDATE $schema.$table SET $addressColumn = ?", blank) }
                        .isInstanceOf(DataIntegrityViolationException::class.java)
                        .hasMessageContaining(addressConstraint)
                }
                // Change one field at a time, so another invalid field cannot mask a removed CHECK.
                mapOf("provider" to "KAKAO", "external_id" to " ", "name" to " ").forEach { (field, invalid) ->
                    val column = if (table == "feed_advertisements") "place_$field" else field
                    val constraint = if (table == "feed_advertisements") {
                        "${constraintPrefix}_${field}_check"
                    } else {
                        "${constraintPrefix}_$field"
                    }
                    assertThatThrownBy { jdbc.update("UPDATE $schema.$table SET $column = ?", invalid) }
                        .isInstanceOf(DataIntegrityViolationException::class.java)
                        .hasMessageContaining(constraint)
                }
                assertThat(jdbc.queryForMap("SELECT $nameColumn, $addressColumn FROM $schema.$table"))
                    .containsEntry(nameColumn, "여의도")
                    .containsEntry(addressColumn, null)
            }
            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun checks(schema: String): List<Map<String, Any?>> = jdbc.queryForList(
        """SELECT t.relname, c.conname, pg_get_constraintdef(c.oid) AS definition
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = ? AND c.contype = 'c'
                AND t.relname IN ('bundle_locations', 'pinned_places', 'feed_advertisements')
            ORDER BY t.relname, c.conname""",
        schema
    )

    private fun flyway(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
