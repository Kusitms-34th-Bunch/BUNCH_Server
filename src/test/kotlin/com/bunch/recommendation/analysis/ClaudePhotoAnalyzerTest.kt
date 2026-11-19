package com.bunch.recommendation.analysis

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.bunch.global.image.PhotoImage
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure.Reason
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.Reading
import com.bunch.recommendation.service.analysis.ClaudeMessageSender
import com.bunch.recommendation.service.analysis.ClaudePhotoAnalyzer
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

/** The request to the model and the reading of its answer, with a sender that answers by itself (no network). */
class ClaudePhotoAnalyzerTest {
    private val image = PhotoImage(byteArrayOf(1, 2, 3, 4), 4, 1)
    private val answer = ScriptedPhotoAnalyzer.ANSWER

    private var sent: MessageCreateParams? = null

    private fun analyzer(
        reply: String = message(answer),
        model: String = "claude-haiku-5-5",
        effort: String = "low"
    ) = ClaudePhotoAnalyzer(
        sender = ClaudeMessageSender {
            sent = it
            jsonMapper().readValue(reply, Message::class.java)
        },
        model = model,
        effort = effort
    )

    @Test
    fun `the answer of the model is read and kept as it was written, with the model that gave it`() {
        val result = analyzer().analyze(image)

        assertThat(result.raw).isEqualTo(answer)
        assertThat(result.modelVersion).isEqualTo("claude-haiku-5-5")
        assertThat(result.analysis.clues).containsExactly(Reading(EventClueCode.TENT, 0.9))
        assertThat(result.analysis.activity).isEqualTo(Reading(ActivityCode.OUTDOOR, 0.8))
        assertThat(result.analysis.subject).isEqualTo(PhotoSubject.LANDSCAPE)
        assertThat(result.analysis.people).isEqualTo(PeopleCount.P0)
    }

    @Test
    fun `the photo and the question go to the model as an image and a text`() {
        analyzer().analyze(image)

        val content = json(sent!!.messages()).single().path("content")
        assertThat(content).hasSize(2)
        assertThat(content[0].path("type").asText()).isEqualTo("image")
        assertThat(content[0].path("source").path("type").asText()).isEqualTo("base64")
        assertThat(content[0].path("source").path("media_type").asText()).isEqualTo("image/jpeg")
        assertThat(content[0].path("source").path("data").asText())
            .isEqualTo(Base64.getEncoder().encodeToString(image.bytes))
        assertThat(content[1].path("type").asText()).isEqualTo("text")
    }

    @Test
    fun `the model, the lowest depth of thinking and the form of the answer are asked for`() {
        analyzer().analyze(image)

        val params = sent!!
        val output = json(params.outputConfig().get())
        assertThat(params.model().asString()).isEqualTo("claude-haiku-5-5")
        assertThat(output.path("effort").asText()).isEqualTo("low")
        assertThat(output.path("format").path("type").asText()).isEqualTo("json_schema")
        assertThat(output.path("format").path("schema").path("required").map { it.asText() })
            .containsExactly("clues", "activity", "subject", "people")
        assertThat(params.system().get().asString()).contains("TENT", "OUTDOOR")
    }

    @Test
    fun `the model and the depth of thinking can be changed by the settings`() {
        analyzer(model = "claude-sonnet-5-5", effort = "MEDIUM").analyze(image)

        assertThat(sent!!.model().asString()).isEqualTo("claude-sonnet-5-5")
        assertThat(json(sent!!.outputConfig().get()).path("effort").asText()).isEqualTo("medium")
    }

    @Test
    fun `a refusal of the model is a failure that asking again does not mend`() {
        assertFailure(Reason.REFUSED) { analyzer(message("", stopReason = "refusal")).analyze(image) }
        assertThat(Reason.REFUSED.retryable).isFalse()
    }

    @Test
    fun `an answer that was cut short cannot be trusted`() {
        assertFailure(Reason.INVALID_RESPONSE) {
            analyzer(message("""{"clues":[{"code":"TE""", stopReason = "max_tokens")).analyze(image)
        }
    }

    @Test
    fun `an answer without text, or with text that is not JSON, is not a valid answer`() {
        assertFailure(Reason.INVALID_RESPONSE) { analyzer(message("  ")).analyze(image) }
        assertFailure(Reason.INVALID_RESPONSE) { analyzer(message("Sorry, I cannot say.")).analyze(image) }
        assertFailure(Reason.INVALID_RESPONSE) { analyzer(message("[]")).analyze(image) }
    }

    @Test
    fun `a failure of the sender is passed on as it is`() {
        val analyzer = ClaudePhotoAnalyzer(sender = { throw PhotoAnalysisFailure(Reason.RATE_LIMITED) })

        assertFailure(Reason.RATE_LIMITED) { analyzer.analyze(image) }
    }

    @Test
    fun `the failures that a later request can mend are told from the ones it cannot`() {
        assertThat(Reason.entries.filter { it.retryable })
            .containsExactlyInAnyOrder(Reason.RATE_LIMITED, Reason.UNAVAILABLE, Reason.NOT_CONFIGURED)
    }

    private fun assertFailure(reason: Reason, call: () -> Unit) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PhotoAnalysisFailure::class.java) {
            assertThat(it.reason).isEqualTo(reason)
        }
    }

    private fun json(value: Any): JsonNode = jsonMapper().valueToTree(value)

    // A reply of the Claude API with one text block.
    private fun message(text: String, stopReason: String = "end_turn"): String =
        jsonMapper().writeValueAsString(
            mapOf(
                "id" to "msg_test",
                "type" to "message",
                "role" to "assistant",
                "model" to "claude-haiku-5-5",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
                "stop_reason" to stopReason,
                "stop_sequence" to null,
                "usage" to mapOf("input_tokens" to 1200, "output_tokens" to 90)
            )
        )
}
