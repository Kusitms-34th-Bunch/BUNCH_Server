package com.bunch.recommendation.analysis

import com.bunch.global.image.PhotoImage
import com.bunch.recommendation.service.analysis.PhotoAnalysisImageLoader
import org.springframework.web.client.RestClient

/** A loader that gives a small image and remembers which addresses it was asked for, so no storage is called. */
class ScriptedImageLoader : PhotoAnalysisImageLoader(client = RestClient.create()) {
    val loaded = mutableListOf<String>()

    override fun load(url: String): PhotoImage {
        loaded += url
        return PhotoImage(byteArrayOf(1, 2, 3), 1, 1)
    }
}
