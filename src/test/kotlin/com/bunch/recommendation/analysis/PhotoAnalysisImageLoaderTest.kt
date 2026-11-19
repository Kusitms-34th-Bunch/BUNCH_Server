package com.bunch.recommendation.analysis

import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure.Reason
import com.bunch.recommendation.service.analysis.PhotoAnalysisImageLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** The stored photo made ready for the AI: small, a JPEG, nothing else in the file (specification 4-2). */
class PhotoAnalysisImageLoaderTest {
    private val url = "https://res.example.com/image/upload/photos/a.jpg"
    private lateinit var server: MockRestServiceServer
    private lateinit var loader: PhotoAnalysisImageLoader

    @BeforeEach
    fun setup() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        loader = PhotoAnalysisImageLoader(client = builder.build())
    }

    @Test
    fun `the stored photo is got and shrunk to the long side of the specification`() {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(jpeg(3000, 2000), MediaType.IMAGE_JPEG))

        val image = loader.load(url)

        assertThat(image.width).isEqualTo(1024)
        assertThat(image.height).isEqualTo(683)
        server.verify()
    }

    @Test
    fun `the long side is a setting`() {
        val builder = RestClient.builder()
        val shortServer = MockRestServiceServer.bindTo(builder).build()
        shortServer.expect(requestTo(url)).andRespond(withSuccess(jpeg(3000, 2000), MediaType.IMAGE_JPEG))

        val image = PhotoAnalysisImageLoader(longSide = 512, client = builder.build()).load(url)

        assertThat(image.width).isEqualTo(512)
    }

    @Test
    fun `an address that is not a plain https one is not fetched`() {
        listOf(
            "http://res.example.com/a.jpg",
            "ftp://res.example.com/a.jpg",
            "file:///etc/passwd",
            "https://user:secret@res.example.com/a.jpg",
            "https:///a.jpg",
            "not a url"
        ).forEach { address ->
            assertFailure(Reason.BAD_REQUEST) { loader.load(address) }
        }
        server.verify()
    }

    @Test
    fun `a storage that answers with an error is a failure that asking again can mend`() {
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertFailure(Reason.UNAVAILABLE) { loader.load(url) }
    }

    @Test
    fun `an empty answer is a failure that asking again can mend`() {
        server.expect(requestTo(url)).andRespond(withSuccess(ByteArray(0), MediaType.IMAGE_JPEG))

        assertFailure(Reason.UNAVAILABLE) { loader.load(url) }
    }

    @Test
    fun `bytes that are not an image cannot be analyzed and are not asked again`() {
        server.expect(requestTo(url)).andRespond(withSuccess("not an image".toByteArray(), MediaType.IMAGE_JPEG))

        assertFailure(Reason.BAD_REQUEST) { loader.load(url) }
    }

    private fun assertFailure(reason: Reason, call: () -> Unit) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PhotoAnalysisFailure::class.java) {
            assertThat(it.reason).isEqualTo(reason)
        }
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }
}
