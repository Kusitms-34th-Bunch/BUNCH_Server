package com.bunch.recommendation.service.analysis

import com.bunch.global.image.PhotoImage
import com.bunch.global.image.PhotoShrinker
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure.Reason
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.time.Duration

/**
 * Gets the stored photo and makes it ready to be sent to the AI: its long side is at most [longSide] pixels and the
 * file carries nothing but the pixels ([PhotoShrinker]). The address is the one the photo storage gave when the photo
 * was stored; it is still checked to be a plain https address, and a redirect is not followed, so the request cannot
 * be turned to another place.
 */
@Component
class PhotoAnalysisImageLoader(
    @Value("\${anthropic.image-long-side:$DEFAULT_LONG_SIDE}")
    private val longSide: Int = DEFAULT_LONG_SIDE,
    @Value("\${anthropic.image-timeout-seconds:$DEFAULT_TIMEOUT_SECONDS}")
    timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private val client: RestClient = restClient(timeoutSeconds)
) {
    fun load(url: String): PhotoImage {
        val bytes = download(uriOf(url))
        return PhotoShrinker.shrink(bytes, longSide) ?: throw PhotoAnalysisFailure(Reason.BAD_REQUEST)
    }

    // An address that is not a plain https one is a photo that cannot be analyzed, not one to try again.
    private fun uriOf(url: String): URI {
        val uri = try {
            URI(url)
        } catch (ex: URISyntaxException) {
            throw PhotoAnalysisFailure(Reason.BAD_REQUEST, ex)
        }
        val plain = uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        if (!plain) throw PhotoAnalysisFailure(Reason.BAD_REQUEST)
        return uri
    }

    private fun download(uri: URI): ByteArray {
        val bytes = try {
            client.get().uri(uri).retrieve().body(ByteArray::class.java)
        } catch (ex: RuntimeException) {
            // The storage did not answer, or answered with an error: asking again later can help.
            throw PhotoAnalysisFailure(Reason.UNAVAILABLE, ex)
        }
        if (bytes == null || bytes.isEmpty()) throw PhotoAnalysisFailure(Reason.UNAVAILABLE)
        if (bytes.size > MAX_BYTES) throw PhotoAnalysisFailure(Reason.BAD_REQUEST)
        return bytes
    }

    companion object {
        const val DEFAULT_LONG_SIDE = 1024
        private const val DEFAULT_TIMEOUT_SECONDS = 30L

        // The largest photo the photo box accepts (photo box 3.2.2).
        private const val MAX_BYTES = 30 * 1024 * 1024
    }
}

private fun restClient(timeoutSeconds: Long): RestClient = RestClient.builder()
    .requestFactory(
        JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        ).apply {
            setReadTimeout(Duration.ofSeconds(timeoutSeconds))
        }
    )
    .build()
