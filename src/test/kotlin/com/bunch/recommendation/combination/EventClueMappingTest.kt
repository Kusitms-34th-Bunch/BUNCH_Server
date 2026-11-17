package com.bunch.recommendation.combination

import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.EventClueMapping
import com.bunch.recommendation.domain.combination.EventClueRule
import com.bunch.recommendation.domain.combination.EventType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The default mapping is the table 3-3 of recommended bundle specification. The table is written out here once more as
 * names, as the specification writes them, so a code that is moved, dropped or mistyped in the mapping is found.
 */
class EventClueMappingTest {
    private val table = mapOf(
        EventType.BIRTHDAY to Triple(
            "CANDLE_WHOLE_CAKE BIRTHDAY_GARLAND",
            "WHOLE_CAKE PARTY_HAT BALLOONS GIFT_BOX",
            "BOUQUET TOAST"
        ),
        EventType.GRADUATION to Triple(
            "GRAD_CAP GRAD_GOWN",
            "DIPLOMA CAMPUS_GROUP_PHOTO",
            "BOUQUET"
        ),
        EventType.YEAR_END to Triple(
            "",
            "XMAS_TREE XMAS_DECOR SANTA_HAT XMAS_CAKE",
            "TOAST"
        ),
        EventType.WEDDING to Triple(
            "WEDDING_DRESS BRIDE_WITH_BOUQUET WEDDING_ARCH",
            "CELEBRATION_WREATH WEDDING_PHOTOZONE",
            "BOUQUET FORMAL_GROUP_PHOTO"
        ),
        EventType.CONCERT to Triple(
            "CONCERT_STAGE LIGHTSTICK_CROWD FIREWORKS",
            "CONCERT_TICKET AUDIENCE_SEATS MERCH",
            ""
        ),
        EventType.SPORTS_VIEWING to Triple(
            "STADIUM",
            "CHEER_UNIFORM CHEER_GEAR GAME_TICKET",
            "TOAST"
        ),
        EventType.COMPETITION to Triple(
            "RACE_BIB FINISH_LINE MEDAL",
            "TEAM_UNIFORM PODIUM",
            ""
        ),
        EventType.TRAVEL to Triple(
            "AIRPORT_GATE AIRPLANE_WINDOW TRAIN_PLATFORM_LUGGAGE",
            "LUGGAGE HOTEL_ROOM PASSPORT",
            "TOURIST_SCENERY"
        ),
        EventType.CAMPING to Triple(
            "TENT CAMPING_GEAR BBQ_GRILL",
            "CAMPFIRE CAMPING_CHAIR LANTERN",
            ""
        ),
        EventType.HOLIDAY to Triple(
            "ANCESTRAL_TABLE",
            "HANBOK_GROUP_PHOTO HOLIDAY_FOOD JEON_COOKING SEBAE",
            ""
        ),
        EventType.THEME_PARK to Triple(
            "ROLLER_COASTER FERRIS_WHEEL CAROUSEL",
            "CHARACTER_HEADBAND PARADE WRISTBAND_PASS",
            ""
        )
    )

    private val mapping = EventClueMapping.DEFAULT

    @Test
    fun `every event has the strong, support and weak clues of the table`() {
        assertThat(table.keys).containsExactlyInAnyOrder(*EventType.entries.toTypedArray())

        table.forEach { (type, names) ->
            val rule = mapping.ruleOf(type)
            assertThat(rule.strong).`as`("$type strong").isEqualTo(codes(names.first))
            assertThat(rule.support).`as`("$type support").isEqualTo(codes(names.second))
            assertThat(rule.weak).`as`("$type weak").isEqualTo(codes(names.third))
        }
    }

    @Test
    fun `every clue code is used by an event`() {
        val used = EventType.entries.flatMap { type ->
            val rule = mapping.ruleOf(type)
            rule.strong + rule.support + rule.weak
        }

        assertThat(used.toSet()).containsExactlyInAnyOrder(*EventClueCode.entries.toTypedArray())
    }

    @Test
    fun `only the bouquet and the toast are clues of several events`() {
        val eventsOf = EventClueCode.entries.associateWith { code ->
            EventType.entries.count { type ->
                val rule = mapping.ruleOf(type)
                code in rule.strong || code in rule.support || code in rule.weak
            }
        }

        val shared = eventsOf.filterValues { it > 1 }
        assertThat(shared.keys).containsExactlyInAnyOrder(EventClueCode.BOUQUET, EventClueCode.TOAST)
        assertThat(shared.values).containsOnly(3)
    }

    @Test
    fun `the year end has no strong clue`() {
        assertThat(mapping.ruleOf(EventType.YEAR_END).strong).isEmpty()
    }

    @Test
    fun `an event that is not in a mapping has no clues`() {
        val onlyBirthday = EventClueMapping(
            mapOf(EventType.BIRTHDAY to EventClueRule(strong = setOf(EventClueCode.TENT)))
        )

        assertThat(onlyBirthday.ruleOf(EventType.BIRTHDAY).strong).containsExactly(EventClueCode.TENT)
        assertThat(onlyBirthday.ruleOf(EventType.TRAVEL)).isEqualTo(EventClueRule())
    }

    @Test
    fun `a clue is not two things for one event`() {
        assertThatThrownBy {
            EventClueRule(strong = setOf(EventClueCode.TOAST), weak = setOf(EventClueCode.TOAST))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            EventClueRule(support = setOf(EventClueCode.BOUQUET), weak = setOf(EventClueCode.BOUQUET))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun codes(names: String): Set<EventClueCode> =
        names.split(" ").filter { it.isNotEmpty() }.map { EventClueCode.valueOf(it) }.toSet()
}
