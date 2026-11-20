package com.bunch.notification

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

/**
 * V51 removes the notifications of the type that is no longer made (CHARACTER_GROWN) and nothing else.
 */
@JdbcTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationCharacterGrownRemovalMigrationIntegrationTest @Autowired constructor(
    private val dataSource: DataSource
) {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `V51 removes only the character grown notifications and keeps the other types`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema, "번치 주인")
            val recipientId = insertMember(schema, "수신자")
            val bunchId = insertBunch(schema, ownerId)
            insertMembership(schema, bunchId, ownerId, "OWNER")
            insertMembership(schema, bunchId, recipientId)
            configure(schema, "12").migrate()

            val grownOne = insertNotification(schema, recipientId, bunchId, "CHARACTER_GROWN", "CHARACTER_GROWN:1:1")
            val grownTwo = insertNotification(schema, ownerId, bunchId, "CHARACTER_GROWN", "CHARACTER_GROWN:1:1")
            val published = insertNotification(schema, recipientId, bunchId, "BUNDLE_PUBLISHED", "BUNDLE_PUBLISHED:1")
            val joined = insertNotification(schema, recipientId, bunchId, "MEMBER_JOINED", "MEMBER_JOINED:1")

            configure(schema, "50").migrate()
            assertThat(idsOf(schema)).containsExactlyInAnyOrder(grownOne, grownTwo, published, joined)

            assertThat(configure(schema, "51").migrate().migrationsExecuted).isEqualTo(1)
            assertThat(idsOf(schema)).containsExactlyInAnyOrder(published, joined)
            assertThat(countOfType(schema, "CHARACTER_GROWN")).isZero()
        }
    }

    @Test
    fun `V51 leaves a schema without character grown notifications as it is`() {
        withTarget10 { schema ->
            val ownerId = insertMember(schema, "번치 주인")
            val bunchId = insertBunch(schema, ownerId)
            insertMembership(schema, bunchId, ownerId, "OWNER")
            configure(schema, "12").migrate()
            val published = insertNotification(schema, ownerId, bunchId, "BUNDLE_PUBLISHED", "BUNDLE_PUBLISHED:1")

            configure(schema, "50").migrate()
            assertThat(configure(schema, "51").migrate().migrationsExecuted).isEqualTo(1)

            assertThat(idsOf(schema)).containsExactly(published)
            assertThat(configure(schema, "51").migrate().migrationsExecuted).isZero()
        }
    }

    private fun idsOf(schema: String): List<Long> =
        jdbc.queryForList("SELECT id FROM $schema.notifications ORDER BY id", Long::class.java)

    private fun countOfType(schema: String, type: String): Long = jdbc.queryForObject(
        "SELECT count(*) FROM $schema.notifications WHERE type = ?",
        Long::class.java,
        type
    )!!

    private fun withTarget10(block: (String) -> Unit) {
        val schema = "notification_grown_" + UUID.randomUUID().toString().replace("-", "")
        try {
            assertThat(configure(schema, "10").migrate().migrationsExecuted).isEqualTo(10)
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

    private fun insertBunch(schema: String, ownerId: Long): Long = jdbc.queryForObject(
        """INSERT INTO $schema.bunches
            (name, introduction, visibility, character_type, created_by,
             creation_request_key, creation_fingerprint, created_at)
            VALUES (?, 'migration', 'PRIVATE', 'TYPE_2', ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        "grown-bunch-${UUID.randomUUID()}",
        ownerId,
        UUID.randomUUID(),
        "grown-fingerprint-${UUID.randomUUID()}"
    )!!

    private fun insertMembership(schema: String, bunchId: Long, memberId: Long, role: String = "MEMBER"): Long =
        jdbc.queryForObject(
            """INSERT INTO $schema.bunch_memberships
                (bunch_id, member_id, role, status, joined_at, role_granted_at, last_used_at, join_requests)
                VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}'::jsonb)
                RETURNING id""",
            Long::class.java,
            bunchId,
            memberId,
            role
        )!!

    private fun insertNotification(
        schema: String,
        recipientMemberId: Long,
        bunchId: Long,
        type: String,
        dedupeKey: String
    ): Long = jdbc.queryForObject(
        """INSERT INTO $schema.notifications
            (recipient_member_id, bunch_id, type, dedupe_key, title, body, target, created_at)
            VALUES (?, ?, ?, ?, '제목', '본문', CAST(? AS jsonb), CURRENT_TIMESTAMP)
            RETURNING id""",
        Long::class.java,
        recipientMemberId,
        bunchId,
        type,
        dedupeKey,
        "{\"kind\":\"MEMBERS\",\"bunchId\":$bunchId}"
    )!!
}
