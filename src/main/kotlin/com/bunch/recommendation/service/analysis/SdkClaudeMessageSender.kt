package com.bunch.recommendation.service.analysis

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure.Reason
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Sends the requests with the official library of Anthropic. The key comes from the settings and is never logged. A
 * missing key does not stop the application from starting: the request that needs it fails with
 * [Reason.NOT_CONFIGURED], as a missing photo storage does.
 *
 * The library does not retry by itself. A failed analysis is asked again by the job queue, which has the waiting time
 * and the number of attempts, so the two do not multiply each other.
 */
@Component
class SdkClaudeMessageSender(
    @Value("\${anthropic.api-key:}")
    private val apiKey: String = "",
    @Value("\${anthropic.timeout-seconds:$DEFAULT_TIMEOUT_SECONDS}")
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) : ClaudeMessageSender {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val client: AnthropicClient? by lazy {
        if (apiKey.isBlank()) {
            null
        } else {
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0)
                .build()
        }
    }

    override fun send(params: MessageCreateParams): Message {
        val sender = client ?: throw PhotoAnalysisFailure(Reason.NOT_CONFIGURED)
        try {
            return sender.messages().create(params)
        } catch (ex: RateLimitException) {
            throw failure(Reason.RATE_LIMITED, ex)
        } catch (ex: UnauthorizedException) {
            throw failure(Reason.NOT_CONFIGURED, ex)
        } catch (ex: PermissionDeniedException) {
            throw failure(Reason.NOT_CONFIGURED, ex)
        } catch (ex: BadRequestException) {
            throw failure(Reason.BAD_REQUEST, ex)
        } catch (ex: UnprocessableEntityException) {
            throw failure(Reason.BAD_REQUEST, ex)
        } catch (ex: NotFoundException) {
            throw failure(Reason.BAD_REQUEST, ex)
        } catch (ex: AnthropicInvalidDataException) {
            throw failure(Reason.INVALID_RESPONSE, ex)
        } catch (ex: RuntimeException) {
            // The server's own errors, the connection and the time limit: asking again later can help.
            throw failure(Reason.UNAVAILABLE, ex)
        }
    }

    // Only the class of the exception is logged: its message can carry the address or the key of the request.
    private fun failure(reason: Reason, cause: RuntimeException): PhotoAnalysisFailure {
        logger.warn("Photo analysis request failed: reason={}, error={}", reason, cause.javaClass.simpleName)
        return PhotoAnalysisFailure(reason)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60L
    }
}
