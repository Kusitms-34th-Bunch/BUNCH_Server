package com.bunch.recommendation.analysis

import com.bunch.global.image.PhotoShrinker
import com.bunch.recommendation.domain.analysis.PhotoAnalysisFailure
import com.bunch.recommendation.service.analysis.ClaudeMessageSender
import com.bunch.recommendation.service.analysis.ClaudePhotoAnalyzer
import com.bunch.recommendation.service.analysis.SdkClaudeMessageSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path

/**
 * A manual check against the real Claude API, with a few sample photos (recommended bundle specification 14, V1 to
 * V4). It is never run by a build: it needs the folder of the photos in `BUNCH_LIVE_PHOTO_DIR` and the key in
 * `ANTHROPIC_API_KEY`, and a build has neither. It sends the photos in the folder, at most [MAX_PHOTOS], so it costs
 * money; use only photos with no personal information. The key is read from the environment and never printed.
 *
 * What it prints (to the console and to `build/live-spike/result.txt`): for each photo the codes the model gave with
 * their confidence, the tokens the request used, and the time it took.
 */
@EnabledIfEnvironmentVariable(named = "BUNCH_LIVE_PHOTO_DIR", matches = ".+")
class ClaudeLiveSpikeTest {
    @Test
    fun `the model answers the sample photos in the form of the specification`() {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank(), "ANTHROPIC_API_KEY is not set")

        val photos = photosOf(Path.of(System.getenv("BUNCH_LIVE_PHOTO_DIR")))
        assumeTrue(photos.isNotEmpty(), "no JPEG photo in the folder")

        val report = StringBuilder()
        val usage = mutableListOf<String>()
        val sender = SdkClaudeMessageSender(key)
        val analyzer = ClaudePhotoAnalyzer(
            sender = ClaudeMessageSender { params ->
                sender.send(params).also {
                    usage += "input=${it.usage().inputTokens()} output=${it.usage().outputTokens()}"
                }
            },
            model = System.getenv("ANTHROPIC_MODEL").orEmpty().ifBlank { ClaudePhotoAnalyzer.DEFAULT_MODEL },
            effort = System.getenv("ANTHROPIC_EFFORT").orEmpty().ifBlank { "low" }
        )
        val failures = mutableListOf<String>()

        photos.forEach { photo ->
            val shrunk = PhotoShrinker.shrink(Files.readAllBytes(photo), 1024)
            if (shrunk == null) {
                failures += "${photo.fileName}: the file is not an image"
                report.appendLine("== ${photo.fileName}: not an image")
                return@forEach
            }
            usage.clear()
            val started = System.nanoTime()
            try {
                val result = analyzer.analyze(shrunk)
                val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
                report.appendLine("== ${photo.fileName} (${shrunk.width}x${shrunk.height}, ${"%.1f".format(seconds)}s)")
                report.appendLine("model: ${result.modelVersion}, tokens: ${usage.lastOrNull()}")
                report.appendLine("clues: ${result.analysis.clues.joinToString { "${it.code}(${it.confidence})" }}")
                val sensitive = result.analysis.sensitiveClues.joinToString { "${it.code}(${it.confidence})" }
                report.appendLine("sensitive: $sensitive")
                report.appendLine("activity: ${result.analysis.activity?.let { "${it.code}(${it.confidence})" }}")
                report.appendLine("subject: ${result.analysis.subject}, people: ${result.analysis.people}")
                report.appendLine("raw: ${result.raw}")
            } catch (failure: PhotoAnalysisFailure) {
                failures += "${photo.fileName}: ${failure.reason}"
                report.appendLine("== ${photo.fileName}: FAILED ${failure.reason}")
            }
        }

        val text = report.toString()
        println(text)
        Files.createDirectories(Path.of("build", "live-spike"))
        Files.writeString(Path.of("build", "live-spike", "result.txt"), text)

        assertThat(failures).describedAs("photos that could not be analyzed").isEmpty()
    }

    private fun photosOf(dir: Path): List<Path> = Files.list(dir).use { files ->
        files.filter { Files.isRegularFile(it) }
            .filter { isJpeg(it.fileName.toString()) }
            .sorted()
            .limit(MAX_PHOTOS.toLong())
            .toList()
    }

    private fun isJpeg(name: String): Boolean = name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }

    private companion object {
        const val MAX_PHOTOS = 10
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
