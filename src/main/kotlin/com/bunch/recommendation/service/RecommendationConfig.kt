package com.bunch.recommendation.service

import com.bunch.recommendation.domain.BuiltInSpecialDays
import com.bunch.recommendation.domain.BuiltInTitleSentences
import com.bunch.recommendation.domain.CandidateMatcher
import com.bunch.recommendation.domain.CardSelector
import com.bunch.recommendation.domain.PhotoClusterer
import com.bunch.recommendation.domain.RecommendationSettings
import com.bunch.recommendation.domain.SpecialDayCalendar
import com.bunch.recommendation.domain.TitleContextBuilder
import com.bunch.recommendation.domain.TitleGenerator
import com.bunch.recommendation.domain.TitleSentenceSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Creates the calculation classes of the recommended bundles. They have no Spring annotation, so they stay plain
 * classes that a test can make by hand.
 *
 * [DB 연동 지점] 제목 문장을 DB에서 관리하게 되면 [titleSentenceSource]를, 공휴일을 공공 달력에서 받게 되면
 * [specialDayCalendar]를 DB 구현으로 바꾼다. 제목 생성기는 고치지 않는다.
 */
@Configuration
class RecommendationConfig {
    @Bean
    fun recommendationSettings(): RecommendationSettings = RecommendationSettings()

    @Bean
    fun photoClusterer(settings: RecommendationSettings): PhotoClusterer = PhotoClusterer(settings)

    @Bean
    fun cardSelector(settings: RecommendationSettings): CardSelector = CardSelector(settings)

    @Bean
    fun candidateMatcher(): CandidateMatcher = CandidateMatcher()

    @Bean
    fun titleContextBuilder(): TitleContextBuilder = TitleContextBuilder()

    @Bean
    fun titleSentenceSource(): TitleSentenceSource = BuiltInTitleSentences()

    @Bean
    fun specialDayCalendar(): SpecialDayCalendar = BuiltInSpecialDays()

    @Bean
    fun titleGenerator(
        sentences: TitleSentenceSource,
        calendar: SpecialDayCalendar,
        settings: RecommendationSettings
    ): TitleGenerator = TitleGenerator(sentences, calendar, settings)
}
