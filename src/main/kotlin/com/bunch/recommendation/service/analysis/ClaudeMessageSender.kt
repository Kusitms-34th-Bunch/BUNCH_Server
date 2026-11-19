package com.bunch.recommendation.service.analysis

import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams

/**
 * Sends one request to the Claude API and gives back its answer. It is the one place that touches the network, so the
 * class that builds the request and reads the answer can be tested with a sender that answers by itself.
 *
 * A failure is a [com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure], never the exception of the library.
 */
fun interface ClaudeMessageSender {
    fun send(params: MessageCreateParams): Message
}
