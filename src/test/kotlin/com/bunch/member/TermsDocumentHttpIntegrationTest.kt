package com.bunch.member

import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.bunch.member.domain.AccountStatus
import com.bunch.member.domain.Member
import com.bunch.member.domain.MemberRepository
import com.bunch.member.domain.SignupStatus
import com.bunch.member.domain.TermType
import com.bunch.member.domain.TermsDocument
import com.bunch.member.domain.TermsDocumentRepository
import com.bunch.member.dto.TermsBlock
import com.bunch.member.dto.TermsBlockType
import com.bunch.member.dto.TermsClause
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TermsDocumentHttpIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val members: MemberRepository,
    private val documents: TermsDocumentRepository,
    private val tokens: TokenProvider,
    private val sessions: RefreshTokenRepository,
    private val jdbc: JdbcTemplate
) {
    private val memberIds = mutableListOf<Long>()
    private val sessionIds = mutableListOf<Pair<String, Long>>()
    private val documentIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        sessionIds.forEach { (sid, id) -> sessions.delete(sid, id) }
        memberIds.forEach { members.deleteById(it) }
        documentIds.forEach { jdbc.update("DELETE FROM terms_documents WHERE id = ?", it) }
    }

    @Test
    fun `service terms return the version effective date and clauses in stored order`() {
        document(TermType.SERVICE, clauses = sampleClauses())

        read(TermType.SERVICE, access())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.type").value("SERVICE"))
            .andExpect(jsonPath("$.data.version").value("v1"))
            .andExpect(jsonPath("$.data.effectiveDate").value("2026-11-10"))
            .andExpect(jsonPath("$.data.clauses.length()").value(2))
            .andExpect(jsonPath("$.data.clauses[0].title").value("제 1조 (목적)"))
            .andExpect(jsonPath("$.data.clauses[0].blocks[0].type").value("PARAGRAPH"))
            .andExpect(jsonPath("$.data.clauses[0].blocks[0].text").value("첫째 줄\n둘째 줄 「인용」 😀"))
            .andExpect(jsonPath("$.data.clauses[0].blocks[0].items").doesNotExist())
            .andExpect(jsonPath("$.data.clauses[1].title").value("제 2조 (정의)"))
            .andExpect(jsonPath("$.data.clauses[1].blocks[0].type").value("LIST"))
            .andExpect(jsonPath("$.data.clauses[1].blocks[0].text").doesNotExist())
            .andExpect(jsonPath("$.data.clauses[1].blocks[0].items[0]").value("첫 번째 항목"))
            .andExpect(jsonPath("$.data.clauses[1].blocks[0].items[1]").value("두 번째 항목"))
            .andExpect(jsonPath("$.data.clauses[1].blocks[1].type").value("PARAGRAPH"))
            .andExpect(jsonPath("$.data.id").doesNotExist())
            .andExpect(jsonPath("$.error").isEmpty)
    }

    @Test
    fun `privacy policy has the same shape`() {
        document(TermType.PRIVACY, date = LocalDate.of(2026, 12, 1), clauses = sampleClauses())

        read(TermType.PRIVACY, access())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.type").value("PRIVACY"))
            .andExpect(jsonPath("$.data.version").value("v1"))
            .andExpect(jsonPath("$.data.effectiveDate").value("2026-12-01"))
            .andExpect(jsonPath("$.data.clauses[0].title").value("제 1조 (목적)"))
    }

    @Test
    fun `the version configured as current is served even when other versions exist`() {
        document(TermType.SERVICE, version = "v0", clauses = listOf(clause("옛 약관")))
        document(TermType.SERVICE, version = "v1", clauses = listOf(clause("현재 약관")))
        document(TermType.SERVICE, version = "v2", clauses = listOf(clause("미래 약관")))

        read(TermType.SERVICE, access())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value("v1"))
            .andExpect(jsonPath("$.data.clauses[0].title").value("현재 약관"))
    }

    @Test
    fun `a document that exists only for another version is unavailable`() {
        document(TermType.SERVICE, version = "v0", clauses = listOf(clause("옛 약관")))
        document(TermType.SERVICE, version = "v2", clauses = listOf(clause("미래 약관")))

        read(TermType.SERVICE, access()).expectUnavailable()
    }

    @ParameterizedTest
    @EnumSource(value = TermType::class, names = ["SERVICE", "PRIVACY"])
    fun `missing text for the current version is unavailable`(type: TermType) {
        read(type, access()).expectUnavailable()
    }

    @ParameterizedTest
    @EnumSource(value = TermType::class, names = ["AGE_OVER_14", "DEMOGRAPHICS"])
    fun `types without a document are not found`(type: TermType) {
        read(type, access())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("RESOURCE_001"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNKNOWN", "service", "Privacy", "agreements"])
    fun `an unknown type is invalid input`(type: String) {
        mvc.perform(get("/api/v1/auth/terms/$type").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("COMMON_001"))
    }

    @Test
    fun `stored text that cannot be shown completely is unavailable`() {
        val broken = listOf(
            "blank title" to listOf(TermsClause(" ", listOf(paragraph("본문")))),
            "no blocks" to listOf(TermsClause("제 1조", emptyList())),
            "paragraph without text" to listOf(TermsClause("제 1조", listOf(TermsBlock(TermsBlockType.PARAGRAPH)))),
            "paragraph with blank text" to listOf(TermsClause("제 1조", listOf(paragraph("  ")))),
            "paragraph with items" to listOf(
                TermsClause("제 1조", listOf(TermsBlock(TermsBlockType.PARAGRAPH, "본문", listOf("항목"))))
            ),
            "list without items" to listOf(TermsClause("제 1조", listOf(TermsBlock(TermsBlockType.LIST)))),
            "list with empty items" to listOf(
                TermsClause("제 1조", listOf(TermsBlock(TermsBlockType.LIST, items = emptyList())))
            ),
            "list with blank item" to listOf(
                TermsClause("제 1조", listOf(TermsBlock(TermsBlockType.LIST, items = listOf("항목", " "))))
            ),
            "list with text" to listOf(
                TermsClause("제 1조", listOf(TermsBlock(TermsBlockType.LIST, "본문", listOf("항목"))))
            )
        )
        val access = access()

        val statuses = broken.associate { (reason, clauses) ->
            val saved = document(TermType.SERVICE, clauses = clauses)
            val code = read(TermType.SERVICE, access).andReturn().response.status
            jdbc.update("DELETE FROM terms_documents WHERE id = ?", saved.id)
            reason to code
        }

        assertThat(statuses.filterValues { it != 503 }).isEmpty()
    }

    @Test
    fun `a member who is still signing up can read the terms`() {
        document(TermType.SERVICE)

        read(TermType.SERVICE, access(signup = SignupStatus.IN_PROGRESS)).andExpect(status().isOk)
    }

    @Test
    fun `request without a token is unauthorized`() {
        document(TermType.SERVICE)

        mvc.perform(get("/api/v1/auth/terms/SERVICE"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus::class, names = ["SUSPENDED", "WITHDRAWN"])
    fun `suspended and withdrawn accounts are unauthorized`(accountStatus: AccountStatus) {
        document(TermType.SERVICE)

        read(TermType.SERVICE, access(account = accountStatus))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTH_001"))
    }

    @Test
    fun `the terms list still carries versions only`() {
        document(TermType.SERVICE)

        mvc.perform(get("/api/v1/auth/terms").header("Authorization", "Bearer ${access()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.terms.length()").value(4))
            .andExpect(jsonPath("$.data.terms[0].type").value("SERVICE"))
            .andExpect(jsonPath("$.data.terms[0].version").value("v1"))
            .andExpect(jsonPath("$.data.terms[0].clauses").doesNotExist())
    }

    @Test
    fun `the database rejects a duplicate version another type and an empty clause list`() {
        document(TermType.SERVICE)

        assertThrows<DataIntegrityViolationException> { document(TermType.SERVICE) }
        assertThrows<DataIntegrityViolationException> { insertRaw("AGE_OVER_14", "[{}]") }
        assertThrows<DataIntegrityViolationException> { insertRaw("PRIVACY", "[]") }
        assertThrows<DataIntegrityViolationException> { insertRaw("PRIVACY", "{}") }
    }

    private fun read(type: TermType, access: String): ResultActions =
        mvc.perform(get("/api/v1/auth/terms/${type.name}").header("Authorization", "Bearer $access"))

    private fun ResultActions.expectUnavailable(): ResultActions = andExpect(status().isServiceUnavailable)
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data").isEmpty)
        .andExpect(jsonPath("$.error.code").value("AUTH_017"))

    private fun document(
        type: TermType,
        version: String = "v1",
        date: LocalDate = LocalDate.of(2026, 11, 10),
        clauses: List<TermsClause> = sampleClauses()
    ): TermsDocument = documents.saveAndFlush(
        TermsDocument(type = type, version = version, effectiveDate = date, clauses = clauses)
    ).also { documentIds += it.id }

    private fun insertRaw(type: String, clauses: String) = jdbc.update(
        "INSERT INTO terms_documents (type, version, effective_date, clauses) " +
            "VALUES (?, 'v1', DATE '2026-11-10', ?::jsonb)",
        type,
        clauses
    )

    private fun sampleClauses() = listOf(
        TermsClause("제 1조 (목적)", listOf(paragraph("첫째 줄\n둘째 줄 「인용」 😀"))),
        TermsClause(
            "제 2조 (정의)",
            listOf(
                TermsBlock(TermsBlockType.LIST, items = listOf("첫 번째 항목", "두 번째 항목")),
                paragraph("목록 뒤의 본문")
            )
        )
    )

    private fun clause(title: String) = TermsClause(title, listOf(paragraph("본문")))

    private fun paragraph(text: String) = TermsBlock(TermsBlockType.PARAGRAPH, text)

    private fun access(
        signup: SignupStatus = SignupStatus.COMPLETED,
        account: AccountStatus = AccountStatus.ACTIVE
    ): String {
        val member = members.saveAndFlush(
            Member(
                signupStatus = signup,
                accountStatus = account,
                nickname = "약관${UUID.randomUUID().toString().take(8)}"
            )
        ).also { memberIds += it.id }
        val pair = tokens.generateToken(member.id, member.role.name)
        val sid = tokens.getSessionId(pair.accessToken)
        sessions.save(sid, member.id, pair.refreshToken, 3600)
        sessionIds += sid to member.id
        return pair.accessToken
    }
}
