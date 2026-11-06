package com.bunch.recommendation

import com.bunch.recommendation.domain.BuiltInSpecialDays
import com.bunch.recommendation.domain.BuiltInTitleSentences
import com.bunch.recommendation.domain.RecommendationSettings
import com.bunch.recommendation.domain.SpecialDay
import com.bunch.recommendation.domain.SpecialDayCalendar
import com.bunch.recommendation.domain.TitleContext
import com.bunch.recommendation.domain.TitleGenerator
import com.bunch.recommendation.domain.TitleSentence
import com.bunch.recommendation.domain.TitleSentenceSource
import com.bunch.recommendation.domain.TitleSituation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.regex.Pattern
import kotlin.random.Random

class TitleGeneratorTest {
    private val saturday = LocalDate.of(2026, 10, 17)
    private val wednesday = LocalDate.of(2026, 10, 14)
    private val monday = LocalDate.of(2026, 10, 12)

    // ---- Which situation a title is written for (4.2.2 rule 3-①) ----

    @Test
    fun `a special day comes before every other situation`() {
        val title = generator(days = mapOf(saturday to SpecialDay.CHRISTMAS)).generate(
            context(
                endDate = saturday.plusDays(1),
                nicknames = listOf("가", "나", "다"),
                members = 3,
                earliest = saturday,
                previous = saturday.minusDays(40)
            )
        )

        assertThat(title.sentenceId).isEqualTo("special.christmas.1")
    }

    @Test
    fun `the first gathering comes before everyone a long gap and midnight`() {
        val title = generator().generate(
            context(
                endDate = saturday.plusDays(1),
                nicknames = listOf("가", "나", "다"),
                members = 3,
                earliest = saturday,
                previous = saturday.minusDays(40)
            )
        )

        assertThat(title.sentenceId).isEqualTo("first.1")
    }

    @Test
    fun `everyone comes before a long gap and midnight`() {
        val title = generator().generate(
            context(
                endDate = saturday.plusDays(1),
                nicknames = listOf("가", "나", "다"),
                members = 3,
                previous = saturday.minusDays(40)
            )
        )

        assertThat(title.sentenceId).isEqualTo("everyone.1")
    }

    @Test
    fun `everyone needs three members and every one of them`() {
        val twoOfTwo = generator().generate(context(nicknames = listOf("가", "나"), members = 2, previous = null))
        val twoOfThree = generator().generate(context(nicknames = listOf("가", "나"), members = 3, previous = null))

        assertThat(twoOfTwo.sentenceId).doesNotStartWith("everyone")
        assertThat(twoOfThree.sentenceId).doesNotStartWith("everyone")
    }

    @Test
    fun `a long gap comes before midnight`() {
        val title = generator().generate(context(endDate = saturday.plusDays(1), previous = saturday.minusDays(40)))

        assertThat(title.sentenceId).isEqualTo("long_gap.1")
    }

    @Test
    fun `thirty days are a long gap and twenty nine days are not`() {
        val thirty = generator().generate(context(previous = saturday.minusDays(30)))
        val twentyNine = generator().generate(context(previous = saturday.minusDays(29)))

        assertThat(thirty.sentenceId).isEqualTo("long_gap.1")
        assertThat(twentyNine.sentenceId).startsWith("default.")
    }

    @Test
    fun `a cluster over midnight is a situation of its own`() {
        val title = generator().generate(context(endDate = saturday.plusDays(1)))

        assertThat(title.sentenceId).isEqualTo("midnight.1")
    }

    @Test
    fun `with no other situation the title is about the weekday and the time of day`() {
        val title = generator().generate(context())

        assertThat(title.sentenceId).startsWith("default.")
    }

    // ---- The sentences of the default situation (weekday and time of day) ----

    @Test
    fun `a saturday evening takes the saturday evening sentences and the weekend evening ones`() {
        val ids = (1..60).map { generator(seed = it).generate(context()).sentenceId }.toSet()

        assertThat(ids).containsExactlyInAnyOrder("default.saturday_evening.1", "default.weekend_evening.1")
    }

    @Test
    fun `a monday takes the monday sentences and the weekday ones of its time of day`() {
        val ids = (1..60).map {
            generator(seed = it).generate(context(startDate = monday, times = listOf(LocalTime.of(10, 0)))).sentenceId
        }.toSet()

        assertThat(ids).containsExactlyInAnyOrder("default.monday.1", "default.weekday_morning.1")
    }

    @Test
    fun `a saturday and a sunday are weekend days and a wednesday is not`() {
        fun idOn(date: LocalDate) =
            generator().generate(context(startDate = date, times = listOf(LocalTime.of(10, 0)))).sentenceId

        assertThat(idOn(saturday)).isEqualTo("default.weekend_morning.1")
        assertThat(idOn(saturday.plusDays(1))).isEqualTo("default.weekend_morning.1")
        assertThat(idOn(wednesday)).isEqualTo("default.weekday_morning.1")
    }

    @Test
    fun `the time of day changes at five twelve and seventeen o'clock`() {
        fun idAt(hour: Int, minute: Int) =
            generator().generate(context(startDate = wednesday, times = listOf(LocalTime.of(hour, minute)))).sentenceId

        assertThat(idAt(4, 59)).isEqualTo("default.weekday_dawn.1")
        assertThat(idAt(5, 0)).isEqualTo("default.weekday_morning.1")
        assertThat(idAt(11, 59)).isEqualTo("default.weekday_morning.1")
        assertThat(idAt(12, 0)).isEqualTo("default.weekday_afternoon.1")
        assertThat(idAt(16, 59)).isEqualTo("default.weekday_afternoon.1")
        assertThat(idAt(17, 0)).isEqualTo("default.weekday_evening.1")
    }

    @Test
    fun `the time of day is the middle of the shooting times`() {
        fun idOf(vararg times: LocalTime) =
            generator().generate(context(startDate = wednesday, times = times.toList())).sentenceId

        assertThat(idOf(LocalTime.of(6, 0), LocalTime.of(6, 30), LocalTime.of(22, 0)))
            .isEqualTo("default.weekday_morning.1")
        assertThat(idOf(LocalTime.of(4, 0), LocalTime.of(6, 0))).isEqualTo("default.weekday_morning.1")
        assertThat(idOf(LocalTime.of(4, 0), LocalTime.of(4, 30))).isEqualTo("default.weekday_dawn.1")
        // The mean of 04:00 and 05:50 is 04:55, which is still dawn. The later of the two would be morning.
        assertThat(idOf(LocalTime.of(4, 0), LocalTime.of(5, 50))).isEqualTo("default.weekday_dawn.1")
    }

    @Test
    fun `without a shooting time only the weekday is used`() {
        val title = generator().generate(context(times = emptyList()))

        assertThat(title.sentenceId).isEqualTo("default.no_time.1")
        assertThat(title.title).isEqualTo("토요일의 우리")
    }

    @Test
    fun `without a shooting time a monday also takes the monday sentences`() {
        val ids = (1..60).map {
            generator(seed = it).generate(context(startDate = monday, times = emptyList())).sentenceId
        }.toSet()

        assertThat(ids).containsExactlyInAnyOrder("default.no_time.1", "default.monday.1")
    }

    @Test
    fun `without a shooting time a day that is not a monday takes only the sentences for a missing time`() {
        val ids = (1..40).map {
            generator(seed = it).generate(context(startDate = wednesday, times = emptyList())).sentenceId
        }.toSet()

        assertThat(ids).containsExactly("default.no_time.1")
    }

    // ---- Taking sentences out (4.2.2 rule 3-② and the exceptions) ----

    @Test
    fun `a title that is already in the list is not made again`() {
        val titles = (1..40).map {
            generator(seed = it).generate(context(visible = setOf("토요일 저녁"))).title
        }.toSet()

        assertThat(titles).containsExactly("주말 저녁")
    }

    @Test
    fun `the newest sentences are left out and only as many as the setting says`() {
        val sentences = (1..3).map {
            sentence("default.weekday_evening.$it", TitleSituation.DEFAULT, "weekday_evening", "밤$it")
        }
        val settings = RecommendationSettings(recentSentenceCount = 2)
        val recent = listOf("default.weekday_evening.1", "default.weekday_evening.2", "default.weekday_evening.3")

        val ids = (1..40).map {
            generator(sentences, seed = it, settings = settings)
                .generate(context(startDate = wednesday, recent = recent)).sentenceId
        }.toSet()

        assertThat(ids).containsExactly("default.weekday_evening.3")
    }

    @Test
    fun `when no sentence of the situation is left the sentences with names come before the recent ones`() {
        val sentences = listOf(
            sentence("default.weekday_evening.1", TitleSituation.DEFAULT, "weekday_evening", "평일 밤"),
            sentence("names.any.1", TitleSituation.MEMBER_NAMES, "any", "{A}의 날")
        )

        val title = generator(sentences).generate(
            context(startDate = wednesday, recent = listOf("default.weekday_evening.1"))
        )

        assertThat(title.sentenceId).isEqualTo("names.any.1")
        assertThat(title.title).isEqualTo("지수의 날")
    }

    @Test
    fun `when nothing else is left the recent sentences come back`() {
        val sentences = listOf(sentence("default.weekday_evening.1", TitleSituation.DEFAULT, "weekday_evening", "평일 밤"))

        val title = generator(sentences).generate(
            context(startDate = wednesday, recent = listOf("default.weekday_evening.1"))
        )

        assertThat(title.sentenceId).isEqualTo("default.weekday_evening.1")
    }

    @Test
    fun `when the sentences of the situation are all in the list the default ones are used`() {
        val title = generator().generate(
            context(startDate = wednesday, earliest = wednesday, visible = setOf("첫 문장"))
        )

        assertThat(title.sentenceId).startsWith("default.weekday_evening")
    }

    @Test
    fun `a sentence that is switched off is never made`() {
        val sentences = listOf(
            sentence("default.weekday_evening.1", TitleSituation.DEFAULT, "weekday_evening", "꺼진 문장", active = false),
            sentence("default.weekday_evening.2", TitleSituation.DEFAULT, "weekday_evening", "켜진 문장")
        )

        val titles = (1..40).map { generator(sentences, seed = it).generate(context(startDate = wednesday)).title }

        assertThat(titles.toSet()).containsExactly("켜진 문장")
    }

    @Test
    fun `with no sentence at all there is no title`() {
        assertThatThrownBy { generator(emptyList()).generate(context()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // ---- Sentences with the names of the members ----

    @Test
    fun `a sentence with names is not tried first while the rate is zero`() {
        val sentences = FIXTURE + sentence("names.two.1", TitleSituation.MEMBER_NAMES, "two", "{A}·{B} 케미")

        val ids = (1..60).map { generator(sentences, seed = it).generate(context()).sentenceId }

        assertThat(ids).allMatch { it!!.startsWith("default.") }
    }

    @Test
    fun `a sentence with names is tried first when the rate says so`() {
        val sentences = FIXTURE + sentence("names.two.1", TitleSituation.MEMBER_NAMES, "two", "{A}·{B} 케미")
        val settings = RecommendationSettings(blankSentenceRate = 1.0)

        val ids = (1..30).map { generator(sentences, seed = it, settings = settings).generate(context()).sentenceId }

        assertThat(ids).allMatch { it == "names.two.1" }
    }

    @Test
    fun `the blanks are filled with the weekday the names and the number of the others`() {
        val sentences = listOf(
            sentence("names.two.1", TitleSituation.MEMBER_NAMES, "two", "{A}·{B} 케미"),
            sentence("names.three_plus.1", TitleSituation.MEMBER_NAMES, "three_plus", "{A} 외 {N}명 난리"),
            sentence("names.any.1", TitleSituation.MEMBER_NAMES, "any", "{요일} {A}")
        )
        val settings = RecommendationSettings(blankSentenceRate = 1.0)

        val two = titles(sentences, settings, context(nicknames = listOf("지수", "민호")))
        val three = titles(sentences, settings, context(nicknames = listOf("지수", "민호", "서연")))
        val four = titles(sentences, settings, context(nicknames = listOf("지수", "민호", "서연", "하준")))

        assertThat(two).containsExactlyInAnyOrder("지수·민호 케미", "토요일 지수")
        assertThat(three).containsExactlyInAnyOrder("지수 외 2명 난리", "토요일 지수")
        assertThat(four).containsExactlyInAnyOrder("지수 외 3명 난리", "토요일 지수")
    }

    @Test
    fun `one uploader cannot use the sentences made for two`() {
        val sentences = listOf(
            sentence("names.two.1", TitleSituation.MEMBER_NAMES, "two", "{A}·{B} 케미"),
            sentence("names.any.1", TitleSituation.MEMBER_NAMES, "any", "{A}만 찍음"),
            sentence("names.any.2", TitleSituation.MEMBER_NAMES, "any", "{A}와 {B}")
        )
        val settings = RecommendationSettings(blankSentenceRate = 1.0)

        assertThat(titles(sentences, settings, context(nicknames = listOf("지수")))).containsExactly("지수만 찍음")
    }

    @Test
    fun `a sentence is not made when there is no name to put in`() {
        val sentences = listOf(
            sentence("names.any.1", TitleSituation.MEMBER_NAMES, "any", "{A}의 날"),
            sentence("default.weekend_evening.1", TitleSituation.DEFAULT, "weekend_evening", "주말 저녁")
        )
        val settings = RecommendationSettings(blankSentenceRate = 1.0)

        assertThat(titles(sentences, settings, context(nicknames = emptyList()))).containsExactly("주말 저녁")
    }

    @Test
    fun `a sentence that is too long once the names are in is not made`() {
        val sentences = listOf(
            sentence("names.two.1", TitleSituation.MEMBER_NAMES, "two", "{A}·{B} 케미 못 숨김"),
            sentence("default.weekend_evening.1", TitleSituation.DEFAULT, "weekend_evening", "주말 저녁")
        )
        val settings = RecommendationSettings(blankSentenceRate = 1.0)
        val longNames = listOf("아주아주긴닉네임입니다", "이것도긴닉네임입니다")

        assertThat(titles(sentences, settings, context(nicknames = longNames))).containsExactly("주말 저녁")
    }

    // ---- The whole list ----

    @Test
    fun `the same random numbers make the same title`() {
        val first = generator(seed = 7).generate(context())
        val second = generator(seed = 7).generate(context())

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `a cluster over midnight from new year's eve is written for new year's eve`() {
        val title = realGenerator(1).generate(
            context(startDate = LocalDate.of(2026, 12, 31), endDate = LocalDate.of(2027, 1, 1))
        )

        assertThat(title.sentenceId).startsWith("special.new_years_eve.")
    }

    @Test
    fun `every weekday and time of day finds a sentence of the default situation in the whole list`() {
        // 2026-10-12 is a Monday. These days are no special days, no first gathering and not far from the last one.
        val times = listOf(listOf(3), listOf(8), listOf(14), listOf(20), emptyList())

        (0L..6L).forEach { offset ->
            val date = monday.plusDays(offset)
            times.forEach { hours ->
                val title = realGenerator(1).generate(
                    context(startDate = date, times = hours.map { LocalTime.of(it, 0) })
                )

                assertThat(title.sentenceId).describedAs("$date $hours").startsWith("default.")
            }
        }
    }

    @Test
    fun `no title of the whole list is longer than fifteen letters`() {
        val settings = RecommendationSettings(blankSentenceRate = 0.5)
        val longNames = listOf("아주아주긴닉네임입니다", "이것도긴닉네임입니다", "세번째닉네임")
        val dates = listOf(saturday, monday, wednesday, LocalDate.of(2026, 2, 17), LocalDate.of(2026, 12, 25))

        dates.forEach { date ->
            (1..120).forEach { seed ->
                val title = realGenerator(seed, settings).generate(
                    context(startDate = date, nicknames = longNames, members = 3, earliest = date.minusDays(60))
                )

                assertThat(letters(title.title)).describedAs("$date $seed ${title.title}").isLessThanOrEqualTo(15)
            }
        }
    }

    // ---- Helpers ----

    private fun titles(sentences: List<TitleSentence>, settings: RecommendationSettings, context: TitleContext) =
        (1..60).map { generator(sentences, seed = it, settings = settings).generate(context).title }.toSet()

    private fun generator(
        sentences: List<TitleSentence> = FIXTURE,
        days: Map<LocalDate, SpecialDay> = emptyMap(),
        settings: RecommendationSettings = RecommendationSettings(),
        seed: Int = 1
    ) = TitleGenerator(source(sentences), calendar(days), settings, Random(seed))

    private fun realGenerator(seed: Int, settings: RecommendationSettings = RecommendationSettings()) =
        TitleGenerator(BuiltInTitleSentences(), BuiltInSpecialDays(), settings, Random(seed))

    private fun source(sentences: List<TitleSentence>) = object : TitleSentenceSource {
        override fun sentences() = sentences
    }

    private fun calendar(days: Map<LocalDate, SpecialDay>) = object : SpecialDayCalendar {
        override fun specialDay(date: LocalDate) = days[date]
    }

    private fun context(
        startDate: LocalDate = saturday,
        endDate: LocalDate? = null,
        times: List<LocalTime> = listOf(LocalTime.of(19, 0)),
        nicknames: List<String> = listOf("지수", "민호"),
        members: Int = 5,
        earliest: LocalDate = LocalDate.of(2026, 1, 3),
        previous: LocalDate? = startDate.minusDays(7),
        visible: Set<String> = emptySet(),
        recent: List<String> = emptyList()
    ) = TitleContext(startDate, endDate, times, nicknames, members, earliest, previous, visible, recent)

    private fun sentence(
        id: String,
        situation: TitleSituation,
        detail: String,
        text: String,
        active: Boolean = true
    ) = TitleSentence(id, situation, detail, text, active)

    private fun letters(text: String): Long = Pattern.compile("\\X").matcher(text).results().count()

    private companion object {
        val FIXTURE = listOf(
            TitleSentence("special.christmas.1", TitleSituation.SPECIAL_DAY, "christmas", "성탄 문장"),
            TitleSentence("first.1", TitleSituation.FIRST_GATHERING, "", "첫 문장"),
            TitleSentence("everyone.1", TitleSituation.EVERYONE, "", "전원 문장"),
            TitleSentence("long_gap.1", TitleSituation.AFTER_LONG_GAP, "", "오랜만 문장"),
            TitleSentence("midnight.1", TitleSituation.PAST_MIDNIGHT, "", "자정 문장"),
            TitleSentence("default.saturday_evening.1", TitleSituation.DEFAULT, "saturday_evening", "토요일 저녁"),
            TitleSentence("default.weekend_evening.1", TitleSituation.DEFAULT, "weekend_evening", "주말 저녁"),
            TitleSentence("default.weekend_morning.1", TitleSituation.DEFAULT, "weekend_morning", "주말 아침"),
            TitleSentence("default.monday.1", TitleSituation.DEFAULT, "monday", "월요일 문장"),
            TitleSentence("default.weekday_dawn.1", TitleSituation.DEFAULT, "weekday_dawn", "평일 새벽"),
            TitleSentence("default.weekday_morning.1", TitleSituation.DEFAULT, "weekday_morning", "평일 아침"),
            TitleSentence("default.weekday_afternoon.1", TitleSituation.DEFAULT, "weekday_afternoon", "평일 낮"),
            TitleSentence("default.weekday_evening.1", TitleSituation.DEFAULT, "weekday_evening", "평일 밤"),
            TitleSentence("default.no_time.1", TitleSituation.DEFAULT, "no_time", "{요일}의 우리")
        )
    }
}
