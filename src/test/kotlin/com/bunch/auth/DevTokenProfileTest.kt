package com.bunch.auth

import com.bunch.auth.controller.DevTokenController
import com.bunch.auth.service.DevTokenService
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.MemberRepository
import com.bunch.member.service.DevMemberRegistry
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DevTokenProfileTest {
    private val context = ApplicationContextRunner()
        .withUserConfiguration(DevTokenController::class.java, DevTokenService::class.java, DevMemberRegistry::class.java)
        .withBean(MemberRepository::class.java, { mock(MemberRepository::class.java) })
        .withBean(EntityManager::class.java, { mock(EntityManager::class.java) })
        .withBean(TokenProvider::class.java, { mock(TokenProvider::class.java) })
        .withBean(RefreshTokenRepository::class.java, { mock(RefreshTokenRepository::class.java) })

    @ParameterizedTest
    @ValueSource(strings = ["local", "dev", "test,local", "test,dev"])
    fun `development profiles register token issuance`(profiles: String) {
        context.withPropertyValues("spring.profiles.active=$profiles").run {
            assertThat(it).hasSingleBean(DevTokenController::class.java)
                .hasSingleBean(DevTokenService::class.java)
                .hasSingleBean(DevMemberRegistry::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["test", "prod", "staging", "local,prod", "dev,prod"])
    fun `other profiles and production combinations exclude token issuance`(profiles: String) {
        context.withPropertyValues("spring.profiles.active=$profiles").run {
            assertThat(it).doesNotHaveBean(DevTokenController::class.java)
                .doesNotHaveBean(DevTokenService::class.java)
                .doesNotHaveBean(DevMemberRegistry::class.java)
        }
    }
}
