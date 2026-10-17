package com.bunch.member.service

import com.bunch.member.domain.*
import com.bunch.global.oidc.AuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class SocialMemberRegistryIntegrationTest @Autowired constructor(
    private val registry: SocialMemberRegistry,
    private val identities: ExternalIdentityRepository,
    private val members: MemberRepository,
    private val flyway: Flyway
) {
    @BeforeEach
    fun clean() {
        identities.deleteAll()
        members.deleteAll()
    }

    @Test
    fun `each provider returns the existing member for the same subject`() {
        AuthProvider.entries.forEach { provider ->
            val first = registry.findOrCreate(provider, "subject")
            val again = registry.findOrCreate(provider, "subject")
            assertThat(again.id).isEqualTo(first.id)
            assertThat(again.role).isEqualTo(MemberRole.ROLE_USER)
        }
        assertThat(members.count()).isEqualTo(3)
        assertThat(identities.count()).isEqualTo(3)
    }

    @Test
    fun `same subject across providers never links accounts`() {
        val ids = AuthProvider.entries.map { registry.findOrCreate(it, "same-subject").id }
        assertThat(ids.toSet()).hasSize(3)
    }

    @Test
    fun `concurrent first login creates only one identity and member`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val ids = executor.invokeAll((1..16).map {
                Callable { registry.findOrCreate(AuthProvider.KAKAO, "concurrent-subject").id }
            }).map { it.get(15, TimeUnit.SECONDS) }
            assertThat(ids.toSet()).hasSize(1)
            assertThat(members.count()).isEqualTo(1)
            assertThat(identities.count()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `migration validates and reruns without additional changes`() {
        flyway.validate()
        assertThat(flyway.migrate().migrationsExecuted).isZero()
    }

    @Test
    fun `email removal preserves existing members and social identities`() {
        val schema = "email_removal_${UUID.randomUUID().toString().replace("-", "")}"
        val jdbc = JdbcTemplate(flyway.configuration.dataSource)
        val previous = Flyway.configure()
            .dataSource(flyway.configuration.dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .target("3")
            .load()

        try {
            previous.migrate()
            val memberId = jdbc.queryForObject(
                "INSERT INTO $schema.members (email, role, nickname) VALUES (?, ?, ?) RETURNING id",
                Long::class.java,
                "unused@example.com",
                "ROLE_USER",
                "existing-member"
            )!!
            jdbc.update(
                """INSERT INTO $schema.external_identity
                    (member_id, provider, provider_subject, email_at_link_time)
                    VALUES (?, ?, ?, ?)""",
                memberId,
                "GOOGLE",
                "existing-subject",
                "unused@example.com"
            )

            val current = Flyway.configure()
                .dataSource(flyway.configuration.dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("4")
                .load()
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1)
            assertThat(
                jdbc.queryForObject(
                    """SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = ? AND
                        ((table_name = 'members' AND column_name = 'email') OR
                         (table_name = 'external_identity' AND column_name = 'email_at_link_time'))""",
                    Long::class.java,
                    schema
                )
            ).isZero()
            assertThat(
                jdbc.queryForObject(
                    """SELECT m.nickname FROM $schema.members m
                        JOIN $schema.external_identity i ON i.member_id = m.id
                        WHERE m.id = ? AND i.provider = 'GOOGLE'
                        AND i.provider_subject = 'existing-subject'""",
                    String::class.java,
                    memberId
                )
            ).isEqualTo("existing-member")
            current.validate()
            assertThat(current.migrate().migrationsExecuted).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
