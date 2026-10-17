package com.bunch.member.service

import com.bunch.member.domain.*
import com.bunch.global.oidc.AuthProvider
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
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
    fun clean() { identities.deleteAll(); members.deleteAll() }

    @Test
    fun `each provider permits missing email and returns existing member`() {
        AuthProvider.entries.forEach { provider ->
            val first = registry.findOrCreate(provider, "subject", null)
            val again = registry.findOrCreate(provider, "subject", "later@example.com")
            assertThat(first.email).isNull()
            assertThat(again.id).isEqualTo(first.id)
            assertThat(again.role).isEqualTo(MemberRole.ROLE_USER)
        }
        assertThat(members.count()).isEqualTo(3)
        assertThat(identities.count()).isEqualTo(3)
    }

    @Test
    fun `same email across providers never links accounts`() {
        val ids = AuthProvider.entries.map { registry.findOrCreate(it, "same-subject", "same@example.com").id }
        assertThat(ids.toSet()).hasSize(3)
    }

    @Test
    fun `concurrent first login creates only one identity and member`() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val ids = executor.invokeAll((1..16).map {
                Callable { registry.findOrCreate(AuthProvider.KAKAO, "concurrent-subject", null).id }
            }).map { it.get(15, TimeUnit.SECONDS) }
            assertThat(ids.toSet()).hasSize(1)
            assertThat(members.count()).isEqualTo(1)
            assertThat(identities.count()).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `migration validates and reruns without additional changes`() {
        flyway.validate()
        assertThat(flyway.migrate().migrationsExecuted).isZero()
    }
}
