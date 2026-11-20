package com.bunch.recommendation.service.analysis

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.bunch.global.image.PhotoImage
import com.bunch.recommendation.domain.analysis.AiAnswerParser
import com.bunch.recommendation.domain.analysis.AnalysisPrompt
import com.bunch.recommendation.domain.analysis.AnalysisSchema
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure.Reason
import com.bunch.recommendation.domain.analysis.PhotoAnalysisResult
import com.bunch.recommendation.domain.analysis.PhotoAnalyzer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Base64

/**
 * Asks a Claude model what one photo shows (recommended bundle specification 4-2, 4-3): one request for the clues, the
 * activity, the subject and the number of people, answered as JSON in the form of [AnalysisSchema]. The model and the
 * depth of its thinking are settings, because the model is chosen by trying it on sample photos (14) and may be
 * changed. The answer is stored as it came and read by [AiAnswerParser].
 *
 * The depth of thinking starts at the lowest: this is a classification of a photo, not a problem to solve. The photo is
 * sent as it is given; shrinking it and removing what is not needed is the work of the one that gives it.
 */
@Service
class ClaudePhotoAnalyzer(
    private val sender: ClaudeMessageSender,
    @Value("\${anthropic.model:$DEFAULT_MODEL}")
    private val model: String = DEFAULT_MODEL,
    @Value("\${anthropic.effort:low}")
    effort: String = "low",
    private val parser: AiAnswerParser = AiAnswerParser()
) : PhotoAnalyzer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val effort: OutputConfig.Effort = OutputConfig.Effort.of(effort.lowercase())

    override fun analyze(image: PhotoImage): PhotoAnalysisResult {
        val message = sender.send(requestOf(image))
        val raw = textOf(message)
        val analysis = parser.parse(raw)
        logger.info(
            "Photo analyzed: model={}, inputTokens={}, outputTokens={}",
            model,
            message.usage().inputTokens(),
            message.usage().outputTokens()
        )
        return PhotoAnalysisResult(analysis, raw, message.model().asString())
    }

    private fun requestOf(image: PhotoImage): MessageCreateParams = MessageCreateParams.builder()
        .model(model)
        .maxTokens(MAX_TOKENS)
        .system(AnalysisPrompt.SYSTEM)
        .outputConfig(
            OutputConfig.builder()
                .effort(effort)
                .format(JsonOutputFormat.builder().schema(schemaOf()).build())
                .build()
        )
        .addUserMessageOfContentBlockParams(
            listOf(
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .data(Base64.getEncoder().encodeToString(image.bytes))
                                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                .build()
                        )
                        .build()
                ),
                ContentBlockParam.ofText(AnalysisPrompt.USER_MESSAGE)
            )
        )
        .build()

    private fun schemaOf(): JsonOutputFormat.Schema {
        val builder = JsonOutputFormat.Schema.builder()
        AnalysisSchema.SCHEMA.forEach { (key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)) }
        return builder.build()
    }

    // The text of the answer. A refusal and an answer that was cut short have no JSON that can be trusted.
    private fun textOf(message: Message): String {
        val stopReason = message.stopReason().orElse(null)
        if (stopReason == StopReason.REFUSAL) throw PhotoAnalysisFailure(Reason.REFUSED)
        if (stopReason == StopReason.MAX_TOKENS) throw PhotoAnalysisFailure(Reason.INVALID_RESPONSE)

        val text = message.content()
            .flatMap { it.text().stream().toList() }
            .joinToString("") { it.text() }
        if (text.isBlank()) throw PhotoAnalysisFailure(Reason.INVALID_RESPONSE)
        return text
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        private const val MAX_TOKENS = 4096L
    }
}
