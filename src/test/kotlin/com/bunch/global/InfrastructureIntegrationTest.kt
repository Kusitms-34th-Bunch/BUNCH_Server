package com.bunch.global

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Duration
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InfrastructureIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val mvc: MockMvc
) {
    @Test
    fun `application connects to PostgreSQL and Redis`() {
        assertThat(jdbc.queryForObject("SELECT 1", Int::class.java)).isEqualTo(1)
        val key = "bunch:infrastructure-test:${UUID.randomUUID()}"
        try {
            redis.opsForValue().set(key, "connected", Duration.ofSeconds(30))
            assertThat(redis.opsForValue().get(key)).isEqualTo("connected")
        } finally {
            redis.delete(key)
        }
    }

    @Test
    fun `local OpenAPI describes the project`() {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.info.title").value("BUNCH API"))
    }

    @Test
    fun `unimplemented product API is protected with the common envelope`() {
        mvc.perform(post("/api/v1/unknown"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }
}
