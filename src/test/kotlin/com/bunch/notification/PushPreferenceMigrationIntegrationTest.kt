package com.bunch.notification

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataAccessException
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
class PushPreferenceMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V35 folds per type settings into categories and leaves the app push toggle as it was`() {
        withSchemaBeforeV35 { schema ->
            val onlyLiked = preference(schema, true, """{"BUNDLE_LIKED": false}""")
            val twoBundleTypes = preference(schema, true, """{"DRAFT_ACTIVITY": false, "BUNDLE_PUBLISHED": false}""")
            val untouched = preference(schema, true, "{}")
            val allTrue = preference(schema, true, """{"FOLLOW_REQUESTED": true, "DRAFT_ACTIVITY": true}""")
            val everyCategory = preference(
                schema,
                true,
                """{"CANDIDATE_CREATED": false, "OWNER_TRANSFERRED": false, "BUNCH_FOLLOWED": false,
                    "SOCIAL_COMMENT_CREATED": false}"""
            )
            val alreadyOff = preference(schema, false, """{"MEMBER_JOINED": false}""")
            val ignoredTypes = preference(
                schema,
                true,
                """{"CHARACTER_GROWN": false, "MEMBER_REMOVED": false, "BUNCH_DELETED": false}"""
            )
            val onlyVisibility = preference(schema, true, """{"BUNCH_VISIBILITY_CHANGED": false}""")

            assertThat(configure(schema, "35").migrate().migrationsExecuted).isEqualTo(1)

            assertThat(categories(schema, onlyLiked)).isEqualTo("""{"REACTION": false}""")
            assertThat(categories(schema, twoBundleTypes)).isEqualTo("""{"BUNDLE_NEWS": false}""")
            assertThat(categories(schema, untouched)).isEqualTo("{}")
            assertThat(categories(schema, allTrue)).isEqualTo("{}")
            assertThat(categories(schema, alreadyOff)).isEqualTo("""{"MEMBER_ROLE": false}""")
            assertThat(categories(schema, ignoredTypes)).isEqualTo("{}")
            assertThat(categories(schema, onlyVisibility)).isEqualTo("{}")
            assertThat(parsed(categories(schema, everyCategory))).containsExactlyInAnyOrder(
                "BUNDLE_NEWS", "MEMBER_ROLE", "FOLLOW", "REACTION"
            )

            assertThat(enabled(schema, onlyLiked)).isTrue()
            assertThat(enabled(schema, twoBundleTypes)).isTrue()
            assertThat(enabled(schema, untouched)).isTrue()
            assertThat(enabled(schema, ignoredTypes)).isTrue()
            assertThat(enabled(schema, onlyVisibility)).isTrue()
            assertThat(enabled(schema, alreadyOff)).isFalse()
            assertThat(enabled(schema, everyCategory)).isTrue()
        }
    }

    @Test
    fun `V35 keeps the former type column and requires categories to be a json object`() {
        withSchemaBeforeV35 { schema ->
            val before = preference(schema, true, """{"BUNDLE_LIKED": false}""")

            configure(schema, "35").migrate()

            assertThat(jdbc.queryForObject(
                "SELECT types::text FROM $schema.push_preferences WHERE member_id = ?",
                String::class.java,
                before
            )).isEqualTo("""{"BUNDLE_LIKED": false}""")
            assertThatThrownBy {
                jdbc.update("UPDATE $schema.push_preferences SET categories = '[]'::jsonb WHERE member_id = ?", before)
            }.isInstanceOf(DataAccessException::class.java)
            assertThat(jdbc.queryForObject(
                "SELECT categories::text FROM $schema.push_preferences WHERE member_id = ?",
                String::class.java,
                before
            )).isEqualTo("""{"REACTION": false}""")
        }
    }

    @Test
    fun `a row inserted after V35 without categories is on for every category`() {
        withSchemaBeforeV35 { schema ->
            configure(schema, "35").migrate()
            val memberId = insertMember(schema)

            jdbc.update(
                "INSERT INTO $schema.push_preferences(member_id, enabled, updated_at) " +
                    "VALUES (?, true, CURRENT_TIMESTAMP)",
                memberId
            )

            assertThat(categories(schema, memberId)).isEqualTo("{}")
        }
    }

    private fun withSchemaBeforeV35(block: (String) -> Unit) {
        val schema = "push_preference_migration_" + UUID.randomUUID().toString().replace("-", "")
        try {
            assertThat(configure(schema, versionBeforeV35()).migrate().migrationsExecuted).isPositive()
            block(schema)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    // The highest migration below V35, so the test does not depend on which version numbers exist before it.
    private fun versionBeforeV35(): String {
        val v35 = MigrationVersion.fromVersion("35")
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .info()
            .all()
            .mapNotNull { it.version }
            .filter { it < v35 }
            .max()
            .version
    }

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()

    private fun insertMember(schema: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', ?) RETURNING id""",
        Long::class.java,
        "push-${UUID.randomUUID().toString().take(8)}"
    )!!

    private fun preference(schema: String, enabled: Boolean, types: String): Long {
        val memberId = insertMember(schema)
        jdbc.update(
            "INSERT INTO $schema.push_preferences(member_id, enabled, types, updated_at) " +
                "VALUES (?, ?, ?::jsonb, CURRENT_TIMESTAMP)",
            memberId,
            enabled,
            types
        )
        return memberId
    }

    private fun categories(schema: String, memberId: Long): String = jdbc.queryForObject(
        "SELECT categories::text FROM $schema.push_preferences WHERE member_id = ?",
        String::class.java,
        memberId
    )!!

    private fun enabled(schema: String, memberId: Long): Boolean = jdbc.queryForObject(
        "SELECT enabled FROM $schema.push_preferences WHERE member_id = ?",
        Boolean::class.java,
        memberId
    )!!

    private fun parsed(json: String): List<String> =
        Regex("\"([A-Z_]+)\": false").findAll(json).map { it.groupValues[1] }.toList()
}
