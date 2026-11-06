package com.bunch.bunch

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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BunchReportMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V18 adds report windows without changing legacy report semantics`() {
        withTarget17 { schema ->
            val ownerId = insertMember(schema, "report-migration-owner")
            val recentActorId = insertMember(schema, "report-migration-recent-actor")
            val oldActorId = insertMember(schema, "report-migration-old-actor")
            val recentReporterId = insertBunch(schema, "report-migration-recent-reporter", ownerId)
            val recentTargetId = insertBunch(schema, "report-migration-recent-target", ownerId)
            val oldReporterId = insertBunch(schema, "report-migration-old-reporter", ownerId)
            val oldTargetId = insertBunch(schema, "report-migration-old-target", ownerId)
            val cascadeReporterId = insertBunch(schema, "report-migration-cascade-reporter", ownerId)
            val cascadeTargetId = insertBunch(schema, "report-migration-cascade-target", ownerId)
            val recentCreatedAt = Instant.parse("2026-11-05T12:00:00Z")
            val oldCreatedAt = Instant.parse("2026-10-01T12:00:00Z")
            val recentReviewedAt = Instant.parse("2026-11-05T12:30:00Z")

            val recentReportId = insertLegacyReport(
                schema = schema,
                reporterBunchId = recentReporterId,
                targetBunchId = recentTargetId,
                reportedByMemberId = recentActorId,
                reason = "SPAM",
                detail = "recent report detail",
                status = "REVIEWING",
                createdAt = recentCreatedAt,
                reviewedAt = recentReviewedAt
            )
            val oldReportId = insertLegacyReport(
                schema = schema,
                reporterBunchId = oldReporterId,
                targetBunchId = oldTargetId,
                reportedByMemberId = oldActorId,
                reason = "OTHER",
                detail = null,
                status = "RECEIVED",
                createdAt = oldCreatedAt,
                reviewedAt = null
            )
            val legacyReports = reportRows(schema)

            val migration = configure(schema, "18")
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1)

            assertThat(columnNames(schema, "bunch_profile_reports")).containsExactlyInAnyOrder(
                "id",
                "reporter_bunch_id",
                "target_bunch_id",
                "reported_by_member_id",
                "reason",
                "detail",
                "status",
                "created_at",
                "reviewed_at",
                "window_started_at"
            )
            assertThat(columnDefinition(schema, "bunch_profile_reports", "window_started_at"))
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "NO")
            assertThat(reportRows(schema)).isEqualTo(legacyReports)
            assertThat(windowStartedAtMatchesCreatedAt(schema, recentReportId)).isTrue()
            assertThat(windowStartedAtMatchesCreatedAt(schema, oldReportId)).isTrue()

            val indexDefinition = indexDefinition(schema, "bunch_profile_reports", "ix_bunch_profile_report_window")
                .replace(Regex("\\s+"), " ")
                .lowercase()
            assertThat(indexCount(schema, "bunch_profile_reports", "ix_bunch_profile_report_window")).isEqualTo(1)
            assertThat(indexDefinition).contains(
                "reporter_bunch_id, target_bunch_id, window_started_at desc, id desc"
            )

            // The service owns the third-report quota; migration storage must allow the second row for one pair.
            val explicitWindowStartedAt = Instant.parse("2026-11-06T00:00:00Z")
            val secondReportId = insertWindowedReport(
                schema = schema,
                reporterBunchId = recentReporterId,
                targetBunchId = recentTargetId,
                reportedByMemberId = oldActorId,
                reason = "HARASSMENT",
                detail = "second report in the pair window",
                status = "RECEIVED",
                createdAt = recentCreatedAt.plusSeconds(3600),
                reviewedAt = null,
                windowStartedAt = explicitWindowStartedAt
            )
            assertThat(jdbc.queryForObject(
                """SELECT count(*) FROM $schema.bunch_profile_reports
                    WHERE reporter_bunch_id = ? AND target_bunch_id = ?""",
                Long::class.java,
                recentReporterId,
                recentTargetId
            )).isEqualTo(2L)
            assertThat(timestamp(schema, "window_started_at", secondReportId))
                .isEqualTo(Timestamp.from(explicitWindowStartedAt))
            assertThat(jdbc.queryForObject(
                "SELECT window_started_at >= created_at FROM $schema.bunch_profile_reports WHERE id = ?",
                Boolean::class.java,
                secondReportId
            )).isTrue()

            assertThatThrownBy {
                insertWindowedReport(
                    schema = schema,
                    reporterBunchId = Long.MAX_VALUE,
                    targetBunchId = cascadeTargetId,
                    reportedByMemberId = oldActorId,
                    reason = "SPAM",
                    detail = "invalid reporter foreign key",
                    status = "RECEIVED",
                    createdAt = explicitWindowStartedAt,
                    reviewedAt = null,
                    windowStartedAt = explicitWindowStartedAt
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertWindowedReport(
                    schema = schema,
                    reporterBunchId = cascadeReporterId,
                    targetBunchId = Long.MAX_VALUE,
                    reportedByMemberId = oldActorId,
                    reason = "SPAM",
                    detail = "invalid target foreign key",
                    status = "RECEIVED",
                    createdAt = explicitWindowStartedAt,
                    reviewedAt = null,
                    windowStartedAt = explicitWindowStartedAt
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertWindowedReport(
                    schema = schema,
                    reporterBunchId = cascadeReporterId,
                    targetBunchId = cascadeTargetId,
                    reportedByMemberId = Long.MAX_VALUE,
                    reason = "SPAM",
                    detail = "invalid actor foreign key",
                    status = "RECEIVED",
                    createdAt = explicitWindowStartedAt,
                    reviewedAt = null,
                    windowStartedAt = explicitWindowStartedAt
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertWindowedReport(
                    schema = schema,
                    reporterBunchId = recentReporterId,
                    targetBunchId = recentReporterId,
                    reportedByMemberId = oldActorId,
                    reason = "SPAM",
                    detail = "self report",
                    status = "RECEIVED",
                    createdAt = explicitWindowStartedAt,
                    reviewedAt = null,
                    windowStartedAt = explicitWindowStartedAt
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertWindowedReport(
                    schema = schema,
                    reporterBunchId = cascadeReporterId,
                    targetBunchId = cascadeTargetId,
                    reportedByMemberId = oldActorId,
                    reason = "SPAM",
                    detail = "invalid status",
                    status = "NOT_A_STATUS",
                    createdAt = explicitWindowStartedAt,
                    reviewedAt = null,
                    windowStartedAt = explicitWindowStartedAt
                )
            }.isInstanceOf(DataAccessException::class.java)
            assertThatThrownBy {
                insertWindowedReport(
                    schema = schema,
                    reporterBunchId = cascadeReporterId,
                    targetBunchId = cascadeTargetId,
                    reportedByMemberId = oldActorId,
                    reason = "SPAM",
                    detail = "null window",
                    status = "RECEIVED",
                    createdAt = explicitWindowStartedAt,
                    reviewedAt = null,
                    windowStartedAt = null
                )
            }.isInstanceOf(DataAccessException::class.java)

            assertThatThrownBy { jdbc.update("DELETE FROM $schema.members WHERE id = ?", recentActorId) }
                .isInstanceOf(DataAccessException::class.java)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_profile_reports WHERE reported_by_member_id = ?",
                Long::class.java,
                recentActorId
            )).isEqualTo(1L)

            val cascadeByReporterId = insertWindowedReport(
                schema = schema,
                reporterBunchId = cascadeReporterId,
                targetBunchId = cascadeTargetId,
                reportedByMemberId = oldActorId,
                reason = "SPAM",
                detail = "reporter cascade",
                status = "RECEIVED",
                createdAt = explicitWindowStartedAt,
                reviewedAt = null,
                windowStartedAt = explicitWindowStartedAt
            )
            val cascadeByTargetId = insertWindowedReport(
                schema = schema,
                reporterBunchId = oldReporterId,
                targetBunchId = cascadeReporterId,
                reportedByMemberId = oldActorId,
                reason = "OTHER",
                detail = "target cascade",
                status = "RECEIVED",
                createdAt = explicitWindowStartedAt,
                reviewedAt = null,
                windowStartedAt = explicitWindowStartedAt
            )
            jdbc.update("DELETE FROM $schema.bunches WHERE id = ?", cascadeReporterId)
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM $schema.bunch_profile_reports WHERE id IN (?, ?)",
                Long::class.java,
                cascadeByReporterId,
                cascadeByTargetId
            )).isZero()

            migration.validate()
            assertThat(migration.migrate().migrationsExecuted).isZero()
            migration.validate()
        }
    }

    private fun withTarget17(block: (String) -> Unit) {
        val schema = "bunch_report_migration_" + UUID.randomUUID().toString().replace("-", "")
        try {
            assertThat(configure(schema, "17").migrate().migrationsExecuted).isEqualTo(17)
            block(schema)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun configure(schema: String, target: String): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()

    private fun insertMember(schema: String, nickname: String): Long = jdbc.queryForObject(
        """INSERT INTO $schema.members (role, signup_status, account_status, nickname)
            VALUES ('ROLE_USER', 'COMPLETED', 'ACTIVE', ?) RETURNING id""",
        Long::class.java,
        nickname
    )!!

    private fun insertBunch(schema: String, name: String, createdByMemberId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, 'migration', 'PRIVATE', 'TYPE_2', ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        name,
        createdByMemberId,
        UUID.randomUUID(),
        "fingerprint-$name"
    )!!

    private fun insertLegacyReport(
        schema: String,
        reporterBunchId: Long,
        targetBunchId: Long,
        reportedByMemberId: Long,
        reason: String,
        detail: String?,
        status: String,
        createdAt: Instant,
        reviewedAt: Instant?
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_profile_reports
            (reporter_bunch_id, target_bunch_id, reported_by_member_id, reason, detail, status, created_at, reviewed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE))
            RETURNING id""",
        Long::class.java,
        reporterBunchId,
        targetBunchId,
        reportedByMemberId,
        reason,
        detail,
        status,
        Timestamp.from(createdAt),
        reviewedAt?.let(Timestamp::from)
    )!!

    private fun insertWindowedReport(
        schema: String,
        reporterBunchId: Long,
        targetBunchId: Long,
        reportedByMemberId: Long,
        reason: String,
        detail: String?,
        status: String,
        createdAt: Instant,
        reviewedAt: Instant?,
        windowStartedAt: Instant?
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunch_profile_reports
            (reporter_bunch_id, target_bunch_id, reported_by_member_id, reason, detail, status,
             created_at, reviewed_at, window_started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMP WITH TIME ZONE),
                    CAST(? AS TIMESTAMP WITH TIME ZONE))
            RETURNING id""",
        Long::class.java,
        reporterBunchId,
        targetBunchId,
        reportedByMemberId,
        reason,
        detail,
        status,
        Timestamp.from(createdAt),
        reviewedAt?.let(Timestamp::from),
        windowStartedAt?.let(Timestamp::from)
    )!!

    private fun reportRows(schema: String): List<Map<String, Any?>> = jdbc.queryForList(
        """SELECT id, reporter_bunch_id, target_bunch_id, reported_by_member_id,
            reason, detail, status, created_at, reviewed_at
            FROM $schema.bunch_profile_reports ORDER BY id"""
    )

    private fun windowStartedAtMatchesCreatedAt(schema: String, reportId: Long): Boolean = jdbc.queryForObject(
        "SELECT window_started_at = created_at FROM $schema.bunch_profile_reports WHERE id = ?",
        Boolean::class.java,
        reportId
    )!!

    private fun timestamp(schema: String, column: String, reportId: Long): Timestamp = jdbc.queryForObject(
        "SELECT $column FROM $schema.bunch_profile_reports WHERE id = ?",
        Timestamp::class.java,
        reportId
    )!!

    private fun columnNames(schema: String, table: String): List<String> = jdbc.query(
        """SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position""",
        { resultSet, _ -> resultSet.getString("column_name") },
        schema,
        table
    )

    private fun columnDefinition(schema: String, table: String, column: String): Map<String, Any?> = jdbc.queryForMap(
        """SELECT data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
        schema,
        table,
        column
    )

    private fun indexCount(schema: String, table: String, index: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM pg_indexes
            WHERE schemaname = ? AND tablename = ? AND indexname = ?""",
        Long::class.java,
        schema,
        table,
        index
    )!!

    private fun indexDefinition(schema: String, table: String, index: String): String = jdbc.queryForObject(
        """SELECT indexdef FROM pg_indexes
            WHERE schemaname = ? AND tablename = ? AND indexname = ?""",
        String::class.java,
        schema,
        table,
        index
    )!!
}
