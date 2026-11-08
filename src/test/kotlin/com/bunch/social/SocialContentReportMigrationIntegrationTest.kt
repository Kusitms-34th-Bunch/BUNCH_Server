package com.bunch.social

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
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
class SocialContentReportMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V25 V26 V27 upgrade preserves existing data allows repeat reports and validates database constraints`() {
        val schema = "content_report_migration_" + UUID.randomUUID().toString().replace("-", "")
        try {
            configure(schema, "25").migrate()
            val member = jdbc.queryForObject("""INSERT INTO $schema.members(role,signup_status,account_status,nickname)
                VALUES ('ROLE_USER','COMPLETED','ACTIVE','report-migration') RETURNING id""", Long::class.java)!!
            val bunch = jdbc.queryForObject("""INSERT INTO $schema.bunches(name,introduction,visibility,character_type,
                created_by,creation_request_key,creation_fingerprint,created_at)
                VALUES ('report-migration','test','PUBLIC','TYPE_2',?,?,'test',CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, member, UUID.randomUUID())!!
            val bundle = jdbc.queryForObject("""INSERT INTO $schema.bundles(bunch_id,created_by,status,title,
                visibility,created_at,updated_at,published_at) VALUES (?,?,'PUBLISHED','test','PUBLIC',
                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id""", Long::class.java, bunch, member)!!
            val comment = jdbc.queryForObject("""INSERT INTO $schema.social_comments(bundle_id,author_member_id,
                acting_bunch_id,badge_name,badge_character_type,content,request_key,fingerprint,created_at)
                VALUES (?,?,?,'badge','TYPE_2','preserved',?,'test',CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bundle, member, bunch, UUID.randomUUID())!!
            val profile = jdbc.queryForObject("""INSERT INTO $schema.bunch_profile_reports(reporter_bunch_id,
                target_bunch_id,reported_by_member_id,reason,detail,created_at,window_started_at)
                VALUES (?,?,?,'SPAM','preserved',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id""",
                Long::class.java, bunch, newBunch(schema, member), member)!!
            val upgrade = configure(schema, "26")
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(upgrade.migrate().migrationsExecuted).isZero()
            upgrade.validate()
            assertThat(jdbc.queryForObject("SELECT content FROM $schema.social_comments WHERE id = ?",
                String::class.java, comment)).isEqualTo("preserved")
            assertThat(jdbc.queryForObject("SELECT detail FROM $schema.bunch_profile_reports WHERE id = ?",
                String::class.java, profile)).isEqualTo("preserved")
            fun insert(kind: String = "BUNDLE", target: Long = bundle, actor: Long = member,
                       reporter: Long = bunch, reason: String = "SPAM", status: String = "RECEIVED"): Long =
                jdbc.queryForObject("""INSERT INTO $schema.social_content_reports(reporter_bunch_id,target_kind,
                    target_id,reported_by_member_id,reason,status,created_at,window_started_at)
                    VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id""",
                    Long::class.java, reporter, kind, target, actor, reason, status)!!
            val first = insert()
            val second = insert()
            assertThat(first).isNotEqualTo(second)
            insert("COMMENT", comment)
            listOf<() -> Any>(
                { insert(kind = "BUNCH") }, { insert(target = 0) }, { insert(reason = "INVALID") },
                { insert(status = "INVALID") }, { insert(actor = Long.MAX_VALUE) },
                { insert(reporter = Long.MAX_VALUE) }
            ).forEach { invalid -> assertThatThrownBy { invalid() }.isInstanceOf(DataAccessException::class.java) }
            assertThatThrownBy { jdbc.update("UPDATE $schema.social_content_reports SET window_started_at = NULL WHERE id = ?", first) }
                .isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy { jdbc.update("DELETE FROM $schema.members WHERE id = ?", member) }
                .isInstanceOf(DataAccessException::class.java)
            jdbc.update("DELETE FROM $schema.bundles WHERE id = ?", bundle)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.social_content_reports", Long::class.java))
                .isEqualTo(3)
            assertThat(configure(schema, "27").migrate().migrationsExecuted).isEqualTo(1)
            val final = configure(schema, "27")
            final.validate()
            assertThat(final.migrate().migrationsExecuted).isZero()
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", bunch)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.social_content_reports", Long::class.java)).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource).locations("classpath:db/migration").schemas(schema).defaultSchema(schema)
        .target(target).load()

    private fun newBunch(schema: String, member: Long): Long = jdbc.queryForObject("""INSERT INTO $schema.bunches
        (name,introduction,visibility,character_type,created_by,creation_request_key,creation_fingerprint,created_at)
        VALUES ('report-migration-target','test','PUBLIC','TYPE_2',?,?,'test',CURRENT_TIMESTAMP) RETURNING id""",
        Long::class.java, member, UUID.randomUUID())!!
}
