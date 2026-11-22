package com.bunch.feed

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
class ReportVisibilityMigrationIntegrationTest @Autowired constructor(private val dataSource: DataSource) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V52 removes derived reporter hides and converts legacy moderated comments without losing reports or replies`() {
        val schema = "report_visibility_" + UUID.randomUUID().toString().replace("-", "")
        try {
            flyway(schema, "51").migrate()
            // The legacy trigger uses an unqualified table name; keep this isolated fixture in its own schema.
            jdbc.execute("ALTER FUNCTION $schema.validate_social_comment_parent() SET search_path = $schema")
            val owner = jdbc.queryForObject("""INSERT INTO $schema.members(role,signup_status,account_status,nickname)
                VALUES ('ROLE_USER','COMPLETED','ACTIVE','migration owner') RETURNING id""", Long::class.java)!!
            val bunch = jdbc.queryForObject("""INSERT INTO $schema.bunches
                (name,introduction,visibility,character_type,created_by,creation_request_key,creation_fingerprint,created_at)
                VALUES ('migration bunch','intro','PUBLIC','TYPE_2',?,?,'fingerprint',now()) RETURNING id""",
                Long::class.java, owner, UUID.randomUUID())!!
            val reporter = jdbc.queryForObject("""INSERT INTO $schema.bunches
                (name,introduction,visibility,character_type,created_by,creation_request_key,creation_fingerprint,created_at)
                VALUES ('reporter bunch','intro','PUBLIC','TYPE_2',?,?,'fingerprint',now()) RETURNING id""",
                Long::class.java, owner, UUID.randomUUID())!!
            val bundle = jdbc.queryForObject("""INSERT INTO $schema.bundles
                (bunch_id,created_by,status,title,visibility,published_at,created_at,updated_at)
                VALUES (?,?,'PUBLISHED','migration','PUBLIC',now(),now(),now()) RETURNING id""",
                Long::class.java, bunch, owner)!!
            val reported = comment(schema, bundle, owner, bunch, "reported only")
            val moderated = comment(schema, bundle, owner, bunch, "moderated text")
            val reply = comment(schema, bundle, owner, bunch, "reply", moderated)
            val alreadyDeleted = comment(schema, bundle, owner, bunch, "already deleted")
            jdbc.update("UPDATE $schema.social_comments SET content='', deleted_at='2026-11-01T00:00:00Z' WHERE id=?", alreadyDeleted)
            val previousDeletedAt = jdbc.queryForObject("SELECT deleted_at FROM $schema.social_comments WHERE id=?",
                java.sql.Timestamp::class.java, alreadyDeleted)
            jdbc.update("""INSERT INTO $schema.social_content_reports
                (reporter_bunch_id,target_kind,target_id,reported_by_member_id,reason,created_at,window_started_at)
                VALUES (?,'COMMENT',?,?,'SPAM',now(),now())""", bunch, reported, owner)
            jdbc.update("""INSERT INTO $schema.bunch_profile_reports
                (reporter_bunch_id,target_bunch_id,reported_by_member_id,reason,created_at,window_started_at)
                VALUES (?,?,?,'SPAM',now(),now())""", reporter, bunch, owner)
            val contentReports = jdbc.queryForList("SELECT * FROM $schema.social_content_reports")
            val profileReports = jdbc.queryForList("SELECT * FROM $schema.bunch_profile_reports")
            listOf(reported, moderated).forEach { commentId ->
                jdbc.update("""INSERT INTO $schema.notifications
                    (recipient_member_id,bunch_id,type,dedupe_key,title,body,target,created_at)
                    VALUES (?,?,'SOCIAL_COMMENT_CREATED',?,'comment','original preview',
                        jsonb_build_object('kind','BUNDLE_COMMENTS','commentId',?::bigint),now())""",
                    owner, bunch, "comment-$commentId", commentId)
            }
            val snapshot = UUID.randomUUID()
            jdbc.update("""INSERT INTO $schema.push_jobs
                (id,recipient_member_id,session_id,bunch_id,dedupe_key,type,payload,available_at,expires_at,created_at)
                VALUES (?,?,?,?,?,'SOCIAL_COMMENT_CREATED',
                    jsonb_build_object('aps',jsonb_build_object('alert',jsonb_build_object('body','original preview')),'target',jsonb_build_object('commentId',?::bigint)),
                    now(),now()+interval '1 hour',now())""", snapshot, owner, UUID.randomUUID(), bunch,
                "snapshot-$snapshot", moderated)
            listOf("BUNDLE" to bundle, "COMMENT" to reported).forEach { (kind, id) ->
                jdbc.update("""INSERT INTO $schema.feed_content_hides(target_kind,target_id,reporter_bunch_id,reason)
                    VALUES (?,?,?,'REPORTED')""", kind, id, bunch)
            }
            listOf(moderated, alreadyDeleted, Long.MAX_VALUE).forEach { id ->
                jdbc.update("INSERT INTO $schema.feed_content_hides(target_kind,target_id,reason) VALUES ('COMMENT',?,'review')", id)
            }
            jdbc.update("INSERT INTO $schema.feed_content_hides(target_kind,target_id,reason) VALUES ('BUNDLE',?,'legacy')", bundle)

            val migration = flyway(schema, "52")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)

            assertThat(jdbc.queryForObject("SELECT content FROM $schema.social_comments WHERE id=?", String::class.java, reported))
                .isEqualTo("reported only")
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM $schema.social_comments WHERE id=?", java.sql.Timestamp::class.java, reported))
                .isNull()
            assertThat(jdbc.queryForObject("SELECT content FROM $schema.social_comments WHERE id=?", String::class.java, moderated))
                .isEmpty()
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM $schema.social_comments WHERE id=?", java.sql.Timestamp::class.java, moderated))
                .isNotNull()
            assertThat(jdbc.queryForObject("SELECT content FROM $schema.social_comments WHERE id=?", String::class.java, reply))
                .isEqualTo("reply")
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM $schema.social_comments WHERE id=?", java.sql.Timestamp::class.java, alreadyDeleted))
                .isEqualTo(previousDeletedAt)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.social_content_reports")).isEqualTo(contentReports)
            assertThat(jdbc.queryForList("SELECT * FROM $schema.bunch_profile_reports")).isEqualTo(profileReports)
            assertThat(jdbc.queryForObject("SELECT body FROM $schema.notifications WHERE dedupe_key=?",
                String::class.java, "comment-$moderated")).isEqualTo("삭제된 댓글입니다")
            assertThat(jdbc.queryForObject("SELECT body FROM $schema.notifications WHERE dedupe_key=?",
                String::class.java, "comment-$reported")).isEqualTo("original preview")
            assertThat(jdbc.queryForObject("SELECT payload #>> '{aps,alert,body}' FROM $schema.push_jobs WHERE id=?",
                String::class.java, snapshot)).isEqualTo("삭제된 댓글입니다")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.feed_content_hides", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM $schema.bundles", Long::class.java)).isEqualTo(1)
            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun comment(schema: String, bundle: Long, owner: Long, bunch: Long, content: String, parent: Long? = null): Long =
        jdbc.queryForObject("""INSERT INTO $schema.social_comments
            (bundle_id,author_member_id,acting_bunch_id,badge_name,badge_character_type,parent_id,content,
             request_key,fingerprint,created_at)
            VALUES (?,?,?,'migration','TYPE_2',?,?,?,'fingerprint','2026-10-01T00:00:00Z') RETURNING id""",
            Long::class.java, bundle, owner, bunch, parent, content, UUID.randomUUID())!!

    private fun flyway(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
}
