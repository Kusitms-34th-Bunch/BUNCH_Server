package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.EventTestSupport.analysis
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.EventClueCode.BOUQUET
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_CAP
import com.bunch.recommendation.domain.combination.EventClues
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoQuality
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.PreparedPhoto
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.SourceType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The clue photos and the person photos an event reads (recommended bundle specification 3-1, 4-3, 5-1, 6-1). */
class EventCluesTest {
    private val clues = EventClues()

    private fun prepared(photo: RecoPhoto): PreparedPhoto = ready(listOf(photo)).photos.single()

    private fun withClue(confidence: Double) =
        photo(1, time = "10:00", analysis = analysis(CANDLE_WHOLE_CAKE, confidence = confidence))

    @ParameterizedTest
    @CsvSource("0.69,false", "0.70,true", "0.95,true")
    fun `a clue is seen from the confidence of 0_70 and not below it`(confidence: Double, seen: Boolean) {
        assertThat(clues.seen(prepared(withClue(confidence))).isNotEmpty()).isEqualTo(seen)
    }

    @Test
    fun `the limit of the confidence is a setting`() {
        val strict = EventClues(CombinationSettings(clueMinConfidence = 0.95))

        assertThat(strict.seen(prepared(withClue(0.9)))).isEmpty()
        assertThat(clues.seen(prepared(withClue(0.9)))).containsExactly(CANDLE_WHOLE_CAKE)
    }

    @Test
    fun `a photo taken with the camera that shows a clue and has a time is a clue photo`() {
        assertThat(clues.isCluePhoto(prepared(withClue(0.9)))).isTrue()
    }

    @Test
    fun `a screenshot, a saved image and a photo without a time are not clue photos`() {
        assertThat(clues.isCluePhoto(prepared(withClue(0.9).copy(source = SourceType.SCREENSHOT)))).isFalse()
        assertThat(clues.isCluePhoto(prepared(withClue(0.9).copy(source = SourceType.SAVED)))).isFalse()
        val dateOnly = photo(1, time = null, analysis = analysis(CANDLE_WHOLE_CAKE))
        assertThat(clues.isCluePhoto(prepared(dateOnly))).isFalse()
    }

    @Test
    fun `the quality and a document do not take a clue photo away`() {
        val blurred = withClue(0.9).copy(quality = PhotoQuality.LOW)
        val document = photo(
            1,
            time = "10:00",
            analysis = analysis(CANDLE_WHOLE_CAKE, subject = PhotoSubject.DOCUMENT)
        )

        assertThat(clues.isCluePhoto(prepared(blurred))).isTrue()
        assertThat(clues.isCluePhoto(prepared(document))).isTrue()
    }

    @Test
    fun `a clue photo of an event shows a clue of that event, whether strong, support or weak`() {
        val cake = prepared(withClue(0.9))
        val bouquet = prepared(photo(2, time = "10:00", analysis = analysis(BOUQUET)))

        assertThat(clues.isCluePhotoOf(cake, EventType.BIRTHDAY)).isTrue()
        assertThat(clues.isCluePhotoOf(cake, EventType.GRADUATION)).isFalse()
        assertThat(clues.isCluePhotoOf(bouquet, EventType.BIRTHDAY)).isTrue()
        assertThat(clues.isCluePhotoOf(bouquet, EventType.GRADUATION)).isTrue()
        assertThat(clues.isCluePhotoOf(bouquet, EventType.TRAVEL)).isFalse()
        assertThat(clues.codesOf(EventType.GRADUATION)).contains(GRAD_CAP, BOUQUET)
    }

    @ParameterizedTest
    @CsvSource(
        "PERSON,P0,false",
        "PERSON,P1,true",
        "PERSON,P3_PLUS,true",
        "FOOD,P3_PLUS,false",
        "LANDSCAPE,P1,false"
    )
    fun `a person photo has a person as its subject and one person or more`(
        subject: PhotoSubject,
        people: PeopleCount,
        expected: Boolean
    ) {
        val shot = photo(1, time = "10:00", analysis = analysis(subject = subject, people = people))

        assertThat(clues.isPersonPhoto(prepared(shot))).isEqualTo(expected)
    }

    @Test
    fun `a photo of which the subject or the count is not known is not a person photo`() {
        assertThat(clues.isPersonPhoto(prepared(photo(1, time = "10:00")))).isFalse()
        val noCount = photo(2, time = "10:00", analysis = analysis(subject = PhotoSubject.PERSON))
        assertThat(clues.isPersonPhoto(prepared(noCount))).isFalse()
    }
}
