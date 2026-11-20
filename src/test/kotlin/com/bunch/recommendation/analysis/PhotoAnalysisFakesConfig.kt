package com.bunch.recommendation.analysis

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Puts the scripted analyzer and loader in place of the real ones, which would call the model service. */
@TestConfiguration
class PhotoAnalysisFakesConfig {
    @Bean
    @Primary
    fun scriptedPhotoAnalyzer(): ScriptedPhotoAnalyzer = ScriptedPhotoAnalyzer()

    @Bean
    @Primary
    fun scriptedImageLoader(): ScriptedImageLoader = ScriptedImageLoader()
}
