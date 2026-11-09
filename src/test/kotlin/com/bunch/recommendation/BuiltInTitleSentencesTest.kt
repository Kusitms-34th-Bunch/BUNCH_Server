package com.bunch.recommendation

import com.bunch.recommendation.domain.BuiltInTitleSentences
import com.bunch.recommendation.domain.TitleSituation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class BuiltInTitleSentencesTest {
    private val sentences = BuiltInTitleSentences().sentences()

    @Test
    fun `has the sentences of the list by situation and detail`() {
        val counts = sentences.groupingBy { it.situation to it.detail }.eachCount()

        assertThat(sentences).hasSize(89)
        assertThat(counts).isEqualTo(EXPECTED_COUNTS)
    }

    @Test
    fun `every id is used once and every sentence is written once`() {
        assertThat(sentences.map { it.id }).doesNotHaveDuplicates()
        assertThat(sentences.map { it.text }).doesNotHaveDuplicates()
    }

    @Test
    fun `an id names its situation and detail and ends with a number`() {
        sentences.forEach { sentence ->
            val parts = sentence.id.split(".")
            assertThat(parts.first()).isEqualTo(CODES.getValue(sentence.situation))
            assertThat(parts.last()).matches("[0-9]+")
            if (sentence.detail.isNotEmpty()) assertThat(parts[1]).isEqualTo(sentence.detail)
        }
    }

    @Test
    fun `every sentence is in use`() {
        assertThat(sentences.filter { !it.active }).isEmpty()
    }

    @Test
    fun `no sentence is longer than fifteen letters with the blanks filled`() {
        sentences.forEach { sentence ->
            val filled = sentence.text
                .replace("{요일}", "토요일")
                .replace("{A}", "가나")
                .replace("{B}", "다라")
                .replace("{N}", "2")
            assertThat(letters(filled)).describedAs(sentence.id).isLessThanOrEqualTo(15)
        }
    }

    @Test
    fun `a sentence has no line break and no space at its ends`() {
        sentences.forEach { sentence ->
            assertThat(sentence.text).describedAs(sentence.id).doesNotContain("\n", "\r")
            assertThat(sentence.text).describedAs(sentence.id).isEqualTo(sentence.text.trim())
        }
    }

    @Test
    fun `no sentence guesses an activity a place the weather or a job or counts photos`() {
        sentences.forEach { sentence ->
            assertThat(sentence.text).describedAs(sentence.id).doesNotContain(*FORBIDDEN)
            assertThat(sentence.text).describedAs(sentence.id).doesNotContainPattern("[0-9]")
        }
    }

    @Test
    fun `a sentence only has the blanks of the list`() {
        sentences.forEach { sentence ->
            val blanks = Regex("\\{[^}]*}").findAll(sentence.text).map { it.value }.toSet()
            assertThat(blanks.all { it in ALLOWED_BLANKS }).describedAs(sentence.id).isTrue()
        }
    }

    @Test
    fun `no blank is followed by a particle that changes with the final consonant`() {
        sentences.forEach { sentence ->
            assertThat(PARTICLE_AFTER_BLANK.containsMatchIn(sentence.text)).describedAs(sentence.id).isFalse()
        }
    }

    private fun letters(text: String): Long = Pattern.compile("\\X").matcher(text).results().count()

    private companion object {
        val CODES = mapOf(
            TitleSituation.SPECIAL_DAY to "special",
            TitleSituation.FIRST_GATHERING to "first",
            TitleSituation.EVERYONE to "everyone",
            TitleSituation.AFTER_LONG_GAP to "long_gap",
            TitleSituation.PAST_MIDNIGHT to "midnight",
            TitleSituation.DEFAULT to "default",
            TitleSituation.MEMBER_NAMES to "names"
        )

        val ALLOWED_BLANKS = setOf("{요일}", "{A}", "{B}", "{N}")

        // A particle right after a blank: 이/가, 와/과, 랑/이랑, 은/는, 을/를, 으로/로. The list joins a blank with
        // 「·」, 「의」 or a space instead, because the particle would have to change with the final consonant of the
        // filled word.
        val PARTICLE_AFTER_BLANK = Regex("\\{(?:요일|A|B|N)}(?:이랑|으로|[이가와과랑은는을를로])")

        // Examples of what the list leaves out (활동·장소·날씨·직업·놀림·술). It is a safety net, not the full rule.
        val FORBIDDEN = arrayOf("러닝", "한강", "비 오", "퇴근", "출근", "여행", "소주", "맥주", "술 ", "운동")

        val EXPECTED_COUNTS: Map<Pair<TitleSituation, String>, Int> = mapOf(
            (TitleSituation.SPECIAL_DAY to "seollal") to 3,
            (TitleSituation.SPECIAL_DAY to "chuseok") to 3,
            (TitleSituation.SPECIAL_DAY to "christmas") to 3,
            (TitleSituation.SPECIAL_DAY to "new_years_eve") to 3,
            (TitleSituation.SPECIAL_DAY to "new_year") to 2,
            (TitleSituation.SPECIAL_DAY to "childrens_day") to 1,
            (TitleSituation.SPECIAL_DAY to "halloween") to 1,
            (TitleSituation.SPECIAL_DAY to "pepero") to 1,
            (TitleSituation.SPECIAL_DAY to "other_holiday") to 3,
            (TitleSituation.FIRST_GATHERING to "") to 6,
            (TitleSituation.EVERYONE to "") to 9,
            (TitleSituation.AFTER_LONG_GAP to "") to 8,
            (TitleSituation.PAST_MIDNIGHT to "") to 8,
            (TitleSituation.DEFAULT to "weekend_dawn") to 2,
            (TitleSituation.DEFAULT to "weekend_morning") to 3,
            (TitleSituation.DEFAULT to "weekend_afternoon") to 3,
            (TitleSituation.DEFAULT to "weekend_evening") to 3,
            (TitleSituation.DEFAULT to "saturday_evening") to 1,
            (TitleSituation.DEFAULT to "sunday_evening") to 2,
            (TitleSituation.DEFAULT to "weekday_dawn") to 1,
            (TitleSituation.DEFAULT to "weekday_morning") to 2,
            (TitleSituation.DEFAULT to "weekday_afternoon") to 2,
            (TitleSituation.DEFAULT to "weekday_evening") to 4,
            (TitleSituation.DEFAULT to "monday") to 2,
            (TitleSituation.DEFAULT to "friday_evening") to 2,
            (TitleSituation.DEFAULT to "no_time") to 3,
            (TitleSituation.MEMBER_NAMES to "two") to 5,
            (TitleSituation.MEMBER_NAMES to "three_plus") to 2,
            (TitleSituation.MEMBER_NAMES to "any") to 1
        )
    }
}
