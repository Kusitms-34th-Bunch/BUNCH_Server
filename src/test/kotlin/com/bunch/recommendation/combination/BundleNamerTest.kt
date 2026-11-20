package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.CombinerTestSupport.TODAY
import com.bunch.recommendation.combination.CombinerTestSupport.meals
import com.bunch.recommendation.combination.CombinerTestSupport.shot
import com.bunch.recommendation.combination.EventTestSupport.ready
import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.BundleKind
import com.bunch.recommendation.domain.combination.BundleNamer
import com.bunch.recommendation.domain.combination.DayPreparation
import com.bunch.recommendation.domain.combination.EstablishedBy
import com.bunch.recommendation.domain.combination.EventType
import com.bunch.recommendation.domain.combination.PhotoRole
import com.bunch.recommendation.domain.combination.RecoBundle
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.RuleTrace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The titles of the bundles of a day (recommended bundle specification 9-1). */
class BundleNamerTest {
    private val namer = BundleNamer()

    private fun bundle(
        day: DayPreparation.Ready,
        ids: List<Long>,
        kind: BundleKind = BundleKind.GENERAL,
        activity: ActivityCode? = ActivityCode.MEAL,
        eventType: EventType = EventType.BIRTHDAY,
        by: EstablishedBy = EstablishedBy.STRONG,
        sub: Int? = null,
        start: Instant? = null
    ): RecoBundle {
        val event = kind == BundleKind.EVENT
        val role = if (event) PhotoRole.RELATED else PhotoRole.GENERAL
        val inBundle = day.photos.filter { it.photoId in ids }
        val instants = inBundle.mapNotNull { it.instant }
        val from = start ?: instants.minOrNull() ?: Instant.parse("2026-10-18T20:00:00Z")
        return RecoBundle(
            dayKey = day.dayKey,
            kind = kind,
            eventType = eventType.takeIf { event },
            establishedBy = by.takeIf { event },
            activity = activity.takeIf { !event },
            subIndex = sub,
            photos = inBundle.map { RecoBundle.BundlePhoto(it.photoId, role) },
            coverPhotoId = null,
            startAt = from,
            endAt = instants.maxOrNull() ?: from,
            photoCount = ids.size,
            uploaderCount = 2,
            hasGroupPhoto = false,
            trace = RuleTrace(day.dayKey, kind, RuleTrace.Counts(ids.size, 2), "reco-cfg-1")
        )
    }

    private fun titleOf(
        photos: List<RecoPhoto>,
        today: LocalDate = TODAY,
        make: (DayPreparation.Ready, List<Long>) -> RecoBundle
    ): String {
        val day = ready(photos)
        val ids = photos.map { it.photoId }
        return namer.name(listOf(make(day, ids)), day, today).single().title!!
    }

    private fun general(
        photos: List<RecoPhoto>,
        activity: ActivityCode? = ActivityCode.MEAL,
        today: LocalDate = TODAY
    ) = titleOf(photos, today) { day, ids -> bundle(day, ids, activity = activity) }

    private fun event(photos: List<RecoPhoto>, type: EventType, by: EstablishedBy = EstablishedBy.STRONG) =
        titleOf(photos) { day, ids -> bundle(day, ids, BundleKind.EVENT, eventType = type, by = by) }

    // --- the four forms (9-1) ---

    @Test
    fun `a general bundle is the date, the part of the day and the name of its activity`() {
        assertThat(general(meals(1, "19:00", 3))).isEqualTo("10월 19일 밤 식사")
    }

    @Test
    fun `an event that stood by a strong clue is the date and the name of the event`() {
        assertThat(event(meals(1, "20:00", 3), EventType.BIRTHDAY)).isEqualTo("10월 19일 생일 파티")
    }

    @Test
    fun `an event that stood by a supporting clue or by the date has the part of the day and no name of the event`() {
        assertThat(event(meals(1, "20:00", 3), EventType.BIRTHDAY, EstablishedBy.SUPPORT)).isEqualTo("10월 19일 밤")
        assertThat(event(meals(1, "12:30", 3), EventType.HOLIDAY, EstablishedBy.DATE_BOOST)).isEqualTo("10월 19일 오후")
    }

    @Test
    fun `a general bundle whose activity gives no name is the date and the part of the day`() {
        assertThat(general(meals(1, "20:00", 3), ActivityCode.OTHER)).isEqualTo("10월 19일 밤")
        assertThat(general(meals(1, "20:00", 3), ActivityCode.TRANSIT)).isEqualTo("10월 19일 밤")
        assertThat(general(meals(1, "20:00", 3), activity = null)).isEqualTo("10월 19일 밤")
    }

    // --- the names (9-1, 3-4) ---

    @Test
    fun `every event has the name of the specification`() {
        val names = mapOf(
            EventType.BIRTHDAY to "생일 파티",
            EventType.GRADUATION to "졸업",
            EventType.YEAR_END to "연말",
            EventType.WEDDING to "결혼식",
            EventType.CONCERT to "공연·페스티벌",
            EventType.SPORTS_VIEWING to "경기 관람",
            EventType.COMPETITION to "대회",
            EventType.TRAVEL to "여행",
            EventType.CAMPING to "캠핑",
            EventType.HOLIDAY to "명절",
            EventType.THEME_PARK to "놀이공원"
        )

        assertThat(names.keys).containsExactlyInAnyOrderElementsOf(EventType.entries)
        names.forEach { (type, name) ->
            assertThat(event(meals(1, "14:00", 3), type)).describedAs(type.name).isEqualTo("10월 19일 $name")
        }
    }

    @Test
    fun `every activity has the name of the specification, or none`() {
        val names = mapOf(
            ActivityCode.MEAL to "식사",
            ActivityCode.CAFE to "카페·디저트",
            ActivityCode.DRINKS to "술자리",
            ActivityCode.CELEBRATION to "기념일",
            ActivityCode.STUDY_WORK to "공부·업무",
            ActivityCode.HOME to "일상",
            ActivityCode.WALK to "산책",
            ActivityCode.SHOW_EXHIBITION to "공연·전시",
            ActivityCode.SPORTS to "운동",
            ActivityCode.SHOPPING to "쇼핑",
            ActivityCode.OTHER to null,
            ActivityCode.SIGHTSEEING to "나들이",
            ActivityCode.OUTDOOR to "아웃도어",
            ActivityCode.TRANSIT to null,
            ActivityCode.UNKNOWN to null
        )

        assertThat(names.keys).containsExactlyInAnyOrderElementsOf(ActivityCode.entries)
        names.forEach { (activity, name) ->
            val expected = if (name == null) "10월 19일 오후" else "10월 19일 오후 $name"
            assertThat(general(meals(1, "14:00", 3), activity)).describedAs(activity.name).isEqualTo(expected)
        }
    }

    @Test
    fun `the year end is the Christmas on the 24th and the 25th of December and the year end on the other days`() {
        fun yearEnd(date: String, time: String = "20:00") =
            event(listOf(shot(1, time, date = date), shot(2, time, date = date)), EventType.YEAR_END)

        assertThat(yearEnd("2026-12-23")).isEqualTo("12월 23일 연말")
        assertThat(yearEnd("2026-12-24")).isEqualTo("12월 24일 크리스마스")
        assertThat(yearEnd("2026-12-25")).isEqualTo("12월 25일 크리스마스")
        assertThat(yearEnd("2026-12-26")).isEqualTo("12월 26일 연말")
        assertThat(yearEnd("2026-12-31")).isEqualTo("12월 31일 연말")
    }

    @Test
    fun `the Christmas is told by the day of the bundle, so a night that goes past midnight stays with its day`() {
        // 01:30 of the 25th is of the day that began on the 24th, and 01:30 of the 26th of the 25th.
        val after24 = listOf(shot(1, "01:30", date = "2026-12-25"), shot(2, "01:40", date = "2026-12-25"))
        val after25 = listOf(shot(1, "01:30", date = "2026-12-26"), shot(2, "01:40", date = "2026-12-26"))
        val after26 = listOf(shot(1, "01:30", date = "2026-12-27"), shot(2, "01:40", date = "2026-12-27"))

        assertThat(event(after24, EventType.YEAR_END)).isEqualTo("12월 24일 크리스마스")
        assertThat(event(after25, EventType.YEAR_END)).isEqualTo("12월 25일 크리스마스")
        assertThat(event(after26, EventType.YEAR_END)).isEqualTo("12월 26일 연말")
    }

    // --- the date ---

    @Test
    fun `the year is in front when it is not the year of today`() {
        val last = meals(1, "20:00", 3, date = "2025-10-19")
        val next = meals(1, "20:00", 3, date = "2027-01-05")

        assertThat(general(last)).isEqualTo("2025년 10월 19일 밤 식사")
        assertThat(general(next)).isEqualTo("2027년 1월 5일 밤 식사")
        assertThat(general(meals(1, "20:00", 3, date = "2026-01-01"))).isEqualTo("1월 1일 밤 식사")
        assertThat(general(meals(1, "20:00", 3, date = "2026-12-31"))).isEqualTo("12월 31일 밤 식사")
    }

    @Test
    fun `the year is read from the day that is given, not from the clock`() {
        val photos = meals(1, "20:00", 3)

        assertThat(general(photos, today = LocalDate.parse("2026-01-01"))).isEqualTo("10월 19일 밤 식사")
        assertThat(general(photos, today = LocalDate.parse("2027-01-01"))).isEqualTo("2026년 10월 19일 밤 식사")
    }

    // --- the part of the day (9-1, 7-3) ---

    @Test
    fun `the part of the day is the one of the morning, the afternoon and the night`() {
        val photos = meals(1, "09:00", 3) + meals(11, "14:00", 3) + meals(21, "21:00", 3)
        val day = ready(photos)

        val titles = namer.name(
            listOf(
                bundle(day, listOf(1, 2, 3)),
                bundle(day, listOf(11, 12, 13), activity = ActivityCode.CAFE),
                bundle(day, listOf(21, 22, 23), activity = ActivityCode.DRINKS)
            ),
            day,
            TODAY
        ).map { it.title }

        assertThat(titles).containsExactly("10월 19일 오전 식사", "10월 19일 오후 카페·디저트", "10월 19일 밤 술자리")
    }

    @Test
    fun `the part of the day is the one most of the photos were taken in`() {
        val twoInTheAfternoon = listOf(shot(1, "09:00"), shot(2, "14:00"), shot(3, "14:30"))
        val twoInTheMorning = listOf(shot(1, "09:00"), shot(2, "09:30"), shot(3, "14:00"))

        assertThat(general(twoInTheAfternoon)).isEqualTo("10월 19일 오후 식사")
        assertThat(general(twoInTheMorning)).isEqualTo("10월 19일 오전 식사")
    }

    @Test
    fun `when as many photos are in two parts the earlier part is the one`() {
        assertThat(general(listOf(shot(1, "09:00"), shot(2, "14:00")))).isEqualTo("10월 19일 오전 식사")
        assertThat(general(listOf(shot(1, "14:00"), shot(2, "21:00")))).isEqualTo("10월 19일 오후 식사")
    }

    @Test
    fun `a photo with a date only does not vote for the part of the day`() {
        val photos = listOf(shot(1, "14:00"), shot(2, null), shot(3, null), shot(4, null))

        assertThat(general(photos)).isEqualTo("10월 19일 오후 식사")
    }

    @Test
    fun `a bundle with no photo that has a time has no part of the day in its title`() {
        val photos = listOf(shot(1, null), shot(2, null))

        assertThat(general(photos)).isEqualTo("10월 19일 식사")
        assertThat(general(photos, ActivityCode.OTHER)).isEqualTo("10월 19일")
    }

    @Test
    fun `the night goes past midnight and is still the night of its day`() {
        val photos = listOf(
            shot(1, "23:30"),
            shot(2, "00:10", date = "2026-10-20"),
            shot(3, "01:30", date = "2026-10-20")
        )

        assertThat(general(photos)).isEqualTo("10월 19일 밤 식사")
    }

    // --- the numbers when two titles of a day are the same (9-1) ---

    private val twoDinners = meals(1, "18:00", 3) + meals(11, "19:30", 3)

    @Test
    fun `two bundles of a day with the same title are numbered in the order they start`() {
        val day = ready(twoDinners)
        val early = bundle(day, listOf(1, 2, 3))
        val late = bundle(day, listOf(11, 12, 13))

        val named = namer.name(listOf(early, late), day, TODAY)

        assertThat(named.map { it.title }).containsExactly("10월 19일 밤 식사 (1)", "10월 19일 밤 식사 (2)")
    }

    @Test
    fun `the numbers follow the time they start and not the order they are given`() {
        val day = ready(twoDinners)
        val early = bundle(day, listOf(1, 2, 3))
        val late = bundle(day, listOf(11, 12, 13))

        val named = namer.name(listOf(late, early), day, TODAY)

        assertThat(named.map { it.title }).containsExactly("10월 19일 밤 식사 (2)", "10월 19일 밤 식사 (1)")
        assertThat(named.map { it.photos.first().photoId }).containsExactly(11L, 1L)
    }

    @Test
    fun `three bundles are numbered one, two and three`() {
        val day = ready(twoDinners + meals(21, "21:00", 3))

        val named = namer.name(
            listOf(bundle(day, listOf(21, 22, 23)), bundle(day, listOf(1, 2, 3)), bundle(day, listOf(11, 12, 13))),
            day,
            TODAY
        )

        assertThat(named.map { it.title })
            .containsExactly("10월 19일 밤 식사 (3)", "10월 19일 밤 식사 (1)", "10월 19일 밤 식사 (2)")
    }

    @Test
    fun `bundles that start at the same time are numbered by their smallest photo number`() {
        val day = ready(meals(1, "19:00", 3) + meals(11, "19:00", 3))
        val start = Instant.parse("2026-10-19T10:00:00Z")

        val named = namer.name(
            listOf(bundle(day, listOf(11, 12, 13), start = start), bundle(day, listOf(1, 2, 3), start = start)),
            day,
            TODAY
        )

        assertThat(named.map { it.title }).containsExactly("10월 19일 밤 식사 (2)", "10월 19일 밤 식사 (1)")
    }

    @Test
    fun `bundles with different titles are not numbered, and each title is numbered by itself`() {
        val photos = twoDinners + meals(21, "21:00", 3) + meals(31, "21:30", 3) + meals(41, "21:45", 3)
        val day = ready(photos)

        val named = namer.name(
            listOf(
                bundle(day, listOf(1, 2, 3)),
                bundle(day, listOf(11, 12, 13)),
                bundle(day, listOf(21, 22, 23), activity = ActivityCode.CAFE),
                bundle(day, listOf(31, 32, 33), activity = ActivityCode.DRINKS),
                bundle(day, listOf(41, 42, 43), activity = ActivityCode.CAFE)
            ),
            day,
            TODAY
        )

        assertThat(named.map { it.title }).containsExactly(
            "10월 19일 밤 식사 (1)",
            "10월 19일 밤 식사 (2)",
            "10월 19일 밤 카페·디저트 (1)",
            "10월 19일 밤 술자리",
            "10월 19일 밤 카페·디저트 (2)"
        )
    }

    @Test
    fun `the parts of a day that was cut for its size are numbered like any bundles`() {
        val day = ready(twoDinners)

        val named = namer.name(
            listOf(bundle(day, listOf(1, 2, 3), sub = 1), bundle(day, listOf(11, 12, 13), sub = 2)),
            day,
            TODAY
        )

        assertThat(named.map { it.title }).containsExactly("10월 19일 밤 식사 (1)", "10월 19일 밤 식사 (2)")
    }

    @Test
    fun `two events of the same name are numbered too`() {
        val day = ready(meals(1, "14:00", 3) + meals(11, "20:00", 3))

        val named = namer.name(
            listOf(
                bundle(day, listOf(1, 2, 3), BundleKind.EVENT),
                bundle(day, listOf(11, 12, 13), BundleKind.EVENT)
            ),
            day,
            TODAY
        )

        assertThat(named.map { it.title }).containsExactly("10월 19일 생일 파티 (1)", "10월 19일 생일 파티 (2)")
    }

    @Test
    fun `an event and a general bundle of the same day do not share a number`() {
        val day = ready(meals(1, "14:00", 3) + meals(11, "20:00", 3))

        val named = namer.name(
            listOf(bundle(day, listOf(1, 2, 3), BundleKind.EVENT), bundle(day, listOf(11, 12, 13))),
            day,
            TODAY
        )

        assertThat(named.map { it.title }).containsExactly("10월 19일 생일 파티", "10월 19일 밤 식사")
    }

    // --- what the namer leaves alone ---

    @Test
    fun `only the title is given, the bundles come back in the order they were given`() {
        val day = ready(twoDinners)
        val bundles = listOf(bundle(day, listOf(11, 12, 13)), bundle(day, listOf(1, 2, 3)))

        val named = namer.name(bundles, day, TODAY)

        assertThat(named.map { it.copy(title = null) }).isEqualTo(bundles)
    }

    @Test
    fun `no bundle gives no title`() {
        assertThat(namer.name(emptyList(), ready(meals(1, "14:00", 3)), TODAY)).isEmpty()
    }
}
