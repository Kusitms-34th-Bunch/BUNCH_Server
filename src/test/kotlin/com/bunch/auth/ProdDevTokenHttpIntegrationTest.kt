package com.bunch.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test", "prod")
class ProdDevTokenHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val jdbc: JdbcTemplate
) {
    @Test
    fun `production rejects development token requests before creating a member`() {
        val before = jdbc.queryForObject("SELECT count(*) FROM members", Long::class.java)

        mvc.perform(
            post("/api/v1/auth/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nickname":"운영테스터"}""")
        ).andExpect(status().isUnauthorized)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM members", Long::class.java)).isEqualTo(before)
    }
}
