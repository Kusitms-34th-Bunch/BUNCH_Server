package com.bunch.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RemovedDevTokenHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val jdbc: JdbcTemplate,
    @Qualifier("requestMappingHandlerMapping")
    private val mappings: RequestMappingHandlerMapping
) {
    @Test
    fun `development token endpoint has no handler and cannot create a member`() {
        assertThat(mappings.handlerMethods.keys.flatMap { it.patternValues })
            .doesNotContain("/api/v1/auth/dev/token")
        val before = jdbc.queryForObject("SELECT count(*) FROM members", Long::class.java)

        mvc.perform(
            post("/api/v1/auth/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"nickname":"임시테스터"}""")
        ).andExpect(status().isUnauthorized)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM members", Long::class.java)).isEqualTo(before)
    }
}
