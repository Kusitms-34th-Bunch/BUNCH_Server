package com.bunch.recommendation.domain.combination

import com.bunch.recommendation.domain.combination.EventClueCode.AIRPLANE_WINDOW
import com.bunch.recommendation.domain.combination.EventClueCode.AIRPORT_GATE
import com.bunch.recommendation.domain.combination.EventClueCode.ANCESTRAL_TABLE
import com.bunch.recommendation.domain.combination.EventClueCode.AUDIENCE_SEATS
import com.bunch.recommendation.domain.combination.EventClueCode.BALLOONS
import com.bunch.recommendation.domain.combination.EventClueCode.BBQ_GRILL
import com.bunch.recommendation.domain.combination.EventClueCode.BIRTHDAY_GARLAND
import com.bunch.recommendation.domain.combination.EventClueCode.BOUQUET
import com.bunch.recommendation.domain.combination.EventClueCode.BRIDE_WITH_BOUQUET
import com.bunch.recommendation.domain.combination.EventClueCode.CAMPFIRE
import com.bunch.recommendation.domain.combination.EventClueCode.CAMPING_CHAIR
import com.bunch.recommendation.domain.combination.EventClueCode.CAMPING_GEAR
import com.bunch.recommendation.domain.combination.EventClueCode.CAMPUS_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.CANDLE_WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.CAROUSEL
import com.bunch.recommendation.domain.combination.EventClueCode.CELEBRATION_WREATH
import com.bunch.recommendation.domain.combination.EventClueCode.CHARACTER_HEADBAND
import com.bunch.recommendation.domain.combination.EventClueCode.CHEER_GEAR
import com.bunch.recommendation.domain.combination.EventClueCode.CHEER_UNIFORM
import com.bunch.recommendation.domain.combination.EventClueCode.CONCERT_STAGE
import com.bunch.recommendation.domain.combination.EventClueCode.CONCERT_TICKET
import com.bunch.recommendation.domain.combination.EventClueCode.DIPLOMA
import com.bunch.recommendation.domain.combination.EventClueCode.FERRIS_WHEEL
import com.bunch.recommendation.domain.combination.EventClueCode.FINISH_LINE
import com.bunch.recommendation.domain.combination.EventClueCode.FIREWORKS
import com.bunch.recommendation.domain.combination.EventClueCode.FORMAL_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.GAME_TICKET
import com.bunch.recommendation.domain.combination.EventClueCode.GIFT_BOX
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_CAP
import com.bunch.recommendation.domain.combination.EventClueCode.GRAD_GOWN
import com.bunch.recommendation.domain.combination.EventClueCode.HANBOK_GROUP_PHOTO
import com.bunch.recommendation.domain.combination.EventClueCode.HOLIDAY_FOOD
import com.bunch.recommendation.domain.combination.EventClueCode.HOTEL_ROOM
import com.bunch.recommendation.domain.combination.EventClueCode.JEON_COOKING
import com.bunch.recommendation.domain.combination.EventClueCode.LANTERN
import com.bunch.recommendation.domain.combination.EventClueCode.LIGHTSTICK_CROWD
import com.bunch.recommendation.domain.combination.EventClueCode.LUGGAGE
import com.bunch.recommendation.domain.combination.EventClueCode.MEDAL
import com.bunch.recommendation.domain.combination.EventClueCode.MERCH
import com.bunch.recommendation.domain.combination.EventClueCode.PARADE
import com.bunch.recommendation.domain.combination.EventClueCode.PARTY_HAT
import com.bunch.recommendation.domain.combination.EventClueCode.PASSPORT
import com.bunch.recommendation.domain.combination.EventClueCode.PODIUM
import com.bunch.recommendation.domain.combination.EventClueCode.RACE_BIB
import com.bunch.recommendation.domain.combination.EventClueCode.ROLLER_COASTER
import com.bunch.recommendation.domain.combination.EventClueCode.SANTA_HAT
import com.bunch.recommendation.domain.combination.EventClueCode.SEBAE
import com.bunch.recommendation.domain.combination.EventClueCode.STADIUM
import com.bunch.recommendation.domain.combination.EventClueCode.TEAM_UNIFORM
import com.bunch.recommendation.domain.combination.EventClueCode.TENT
import com.bunch.recommendation.domain.combination.EventClueCode.TOAST
import com.bunch.recommendation.domain.combination.EventClueCode.TOURIST_SCENERY
import com.bunch.recommendation.domain.combination.EventClueCode.TRAIN_PLATFORM_LUGGAGE
import com.bunch.recommendation.domain.combination.EventClueCode.WEDDING_ARCH
import com.bunch.recommendation.domain.combination.EventClueCode.WEDDING_DRESS
import com.bunch.recommendation.domain.combination.EventClueCode.WEDDING_PHOTOZONE
import com.bunch.recommendation.domain.combination.EventClueCode.WHOLE_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.WRISTBAND_PASS
import com.bunch.recommendation.domain.combination.EventClueCode.XMAS_CAKE
import com.bunch.recommendation.domain.combination.EventClueCode.XMAS_DECOR
import com.bunch.recommendation.domain.combination.EventClueCode.XMAS_TREE

/**
 * Which clues count for which event, and how much (recommended bundle specification 3-3). It is data and not code,
 * because after the release the wrong clues are moved from strong to support or to weak again and again. [DEFAULT] is
 * the table of the specification; another mapping can be given wherever one is needed, and later one that is read from
 * the database. An event that is not in [rules] has no clue and never stands.
 */
data class EventClueMapping(
    val rules: Map<EventType, EventClueRule>
) {
    /** The rule of an event; an event that is not in the mapping has no clues. */
    fun ruleOf(type: EventType): EventClueRule = rules[type] ?: EventClueRule()

    companion object {
        /** The table 3-3 of the specification as it is. */
        val DEFAULT = EventClueMapping(
            mapOf(
                EventType.BIRTHDAY to EventClueRule(
                    strong = setOf(CANDLE_WHOLE_CAKE, BIRTHDAY_GARLAND),
                    support = setOf(WHOLE_CAKE, PARTY_HAT, BALLOONS, GIFT_BOX),
                    weak = setOf(BOUQUET, TOAST)
                ),
                EventType.GRADUATION to EventClueRule(
                    strong = setOf(GRAD_CAP, GRAD_GOWN),
                    support = setOf(DIPLOMA, CAMPUS_GROUP_PHOTO),
                    weak = setOf(BOUQUET)
                ),
                EventType.YEAR_END to EventClueRule(
                    support = setOf(XMAS_TREE, XMAS_DECOR, SANTA_HAT, XMAS_CAKE),
                    weak = setOf(TOAST)
                ),
                EventType.WEDDING to EventClueRule(
                    strong = setOf(WEDDING_DRESS, BRIDE_WITH_BOUQUET, WEDDING_ARCH),
                    support = setOf(CELEBRATION_WREATH, WEDDING_PHOTOZONE),
                    weak = setOf(BOUQUET, FORMAL_GROUP_PHOTO)
                ),
                EventType.CONCERT to EventClueRule(
                    strong = setOf(CONCERT_STAGE, LIGHTSTICK_CROWD, FIREWORKS),
                    support = setOf(CONCERT_TICKET, AUDIENCE_SEATS, MERCH)
                ),
                EventType.SPORTS_VIEWING to EventClueRule(
                    strong = setOf(STADIUM),
                    support = setOf(CHEER_UNIFORM, CHEER_GEAR, GAME_TICKET),
                    weak = setOf(TOAST)
                ),
                EventType.COMPETITION to EventClueRule(
                    strong = setOf(RACE_BIB, FINISH_LINE, MEDAL),
                    support = setOf(TEAM_UNIFORM, PODIUM)
                ),
                EventType.TRAVEL to EventClueRule(
                    strong = setOf(AIRPORT_GATE, AIRPLANE_WINDOW, TRAIN_PLATFORM_LUGGAGE),
                    support = setOf(LUGGAGE, HOTEL_ROOM, PASSPORT),
                    weak = setOf(TOURIST_SCENERY)
                ),
                EventType.CAMPING to EventClueRule(
                    strong = setOf(TENT, CAMPING_GEAR, BBQ_GRILL),
                    support = setOf(CAMPFIRE, CAMPING_CHAIR, LANTERN)
                ),
                EventType.HOLIDAY to EventClueRule(
                    strong = setOf(ANCESTRAL_TABLE),
                    support = setOf(HANBOK_GROUP_PHOTO, HOLIDAY_FOOD, JEON_COOKING, SEBAE)
                ),
                EventType.THEME_PARK to EventClueRule(
                    strong = setOf(ROLLER_COASTER, FERRIS_WHEEL, CAROUSEL),
                    support = setOf(CHARACTER_HEADBAND, PARADE, WRISTBAND_PASS)
                )
            )
        )
    }
}
