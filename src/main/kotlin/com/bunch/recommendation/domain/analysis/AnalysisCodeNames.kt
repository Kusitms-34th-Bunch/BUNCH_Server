package com.bunch.recommendation.domain.analysis

import com.bunch.recommendation.domain.combination.ActivityCode
import com.bunch.recommendation.domain.combination.EventClueCode
import com.bunch.recommendation.domain.combination.PeopleCount
import com.bunch.recommendation.domain.combination.PhotoSubject
import com.bunch.recommendation.domain.combination.SensitiveClueCode

/**
 * What each code means, in the words of the tables of the specification (3-2, 3-4, 3-6). The model is given the code
 * with its name, because a code alone does not tell it what to look for. A test checks that every code has a name, so a
 * code added to the lists cannot reach the model without one.
 */
object AnalysisCodeNames {
    val eventClues: Map<EventClueCode, String> = mapOf(
        EventClueCode.CANDLE_WHOLE_CAKE to "촛불 꽂힌 홀케이크",
        EventClueCode.BIRTHDAY_GARLAND to "생일 가랜드·숫자 풍선",
        EventClueCode.WHOLE_CAKE to "홀케이크(촛불 없음)",
        EventClueCode.PARTY_HAT to "고깔모자",
        EventClueCode.BALLOONS to "풍선",
        EventClueCode.GIFT_BOX to "선물 상자",
        EventClueCode.BOUQUET to "꽃다발",
        EventClueCode.TOAST to "건배",
        EventClueCode.GRAD_CAP to "학사모",
        EventClueCode.GRAD_GOWN to "졸업 가운",
        EventClueCode.DIPLOMA to "학위 증서",
        EventClueCode.CAMPUS_GROUP_PHOTO to "교정 단체사진",
        EventClueCode.XMAS_TREE to "크리스마스 트리",
        EventClueCode.XMAS_DECOR to "크리스마스 장식",
        EventClueCode.SANTA_HAT to "산타 모자",
        EventClueCode.XMAS_CAKE to "크리스마스 케이크",
        EventClueCode.WEDDING_DRESS to "웨딩드레스",
        EventClueCode.BRIDE_WITH_BOUQUET to "부케를 든 신부",
        EventClueCode.WEDDING_ARCH to "웨딩 아치·버진로드",
        EventClueCode.CELEBRATION_WREATH to "축하 화환",
        EventClueCode.WEDDING_PHOTOZONE to "웨딩 포토존",
        EventClueCode.FORMAL_GROUP_PHOTO to "하객 정장 단체사진",
        EventClueCode.CONCERT_STAGE to "공연 무대와 조명",
        EventClueCode.LIGHTSTICK_CROWD to "응원봉을 든 관객",
        EventClueCode.FIREWORKS to "불꽃놀이",
        EventClueCode.CONCERT_TICKET to "공연 티켓",
        EventClueCode.AUDIENCE_SEATS to "객석",
        EventClueCode.MERCH to "굿즈",
        EventClueCode.STADIUM to "경기장 관중석과 경기장",
        EventClueCode.CHEER_UNIFORM to "응원 유니폼",
        EventClueCode.CHEER_GEAR to "응원 도구",
        EventClueCode.GAME_TICKET to "경기 티켓",
        EventClueCode.RACE_BIB to "배번(번호표)",
        EventClueCode.FINISH_LINE to "결승선",
        EventClueCode.MEDAL to "메달",
        EventClueCode.TEAM_UNIFORM to "단체 유니폼",
        EventClueCode.PODIUM to "시상대",
        EventClueCode.AIRPORT_GATE to "공항 탑승구",
        EventClueCode.AIRPLANE_WINDOW to "비행기 창밖",
        EventClueCode.TRAIN_PLATFORM_LUGGAGE to "기차 플랫폼과 캐리어",
        EventClueCode.LUGGAGE to "캐리어",
        EventClueCode.HOTEL_ROOM to "숙소 객실",
        EventClueCode.PASSPORT to "여권",
        EventClueCode.TOURIST_SCENERY to "관광지 풍경",
        EventClueCode.TENT to "텐트",
        EventClueCode.CAMPING_GEAR to "캠핑 장비",
        EventClueCode.BBQ_GRILL to "바비큐 그릴",
        EventClueCode.CAMPFIRE to "모닥불",
        EventClueCode.CAMPING_CHAIR to "캠핑 의자",
        EventClueCode.LANTERN to "랜턴",
        EventClueCode.ANCESTRAL_TABLE to "차례상",
        EventClueCode.HANBOK_GROUP_PHOTO to "한복 단체사진",
        EventClueCode.HOLIDAY_FOOD to "떡국·송편",
        EventClueCode.JEON_COOKING to "전 부치기",
        EventClueCode.SEBAE to "세배",
        EventClueCode.ROLLER_COASTER to "롤러코스터",
        EventClueCode.FERRIS_WHEEL to "관람차",
        EventClueCode.CAROUSEL to "회전목마",
        EventClueCode.CHARACTER_HEADBAND to "캐릭터 머리띠",
        EventClueCode.PARADE to "퍼레이드",
        EventClueCode.WRISTBAND_PASS to "자유이용권 손목띠"
    )

    val sensitiveClues: Map<SensitiveClueCode, String> = mapOf(
        SensitiveClueCode.FUNERAL_WREATH to "근조화환",
        SensitiveClueCode.MOURNING_CLOTHES to "상복",
        SensitiveClueCode.FUNERAL_ALTAR to "빈소",
        SensitiveClueCode.MEMORIAL_PORTRAIT to "영정",
        SensitiveClueCode.HOSPITAL_ROOM to "병실",
        SensitiveClueCode.HOSPITAL_BED to "병상",
        SensitiveClueCode.IV_DRIP to "링거",
        SensitiveClueCode.SURGICAL_GOWN to "수술복"
    )

    /** The activities the model may choose from. [ActivityCode.UNKNOWN] is not one of them: the server decides it. */
    val activities: Map<ActivityCode, String> = mapOf(
        ActivityCode.MEAL to "식사",
        ActivityCode.CAFE to "카페·디저트",
        ActivityCode.DRINKS to "술자리·파티",
        ActivityCode.CELEBRATION to "기념일·행사",
        ActivityCode.STUDY_WORK to "공부·업무",
        ActivityCode.HOME to "집·일상",
        ActivityCode.WALK to "산책",
        ActivityCode.SHOW_EXHIBITION to "공연·전시",
        ActivityCode.SPORTS to "운동·스포츠(러닝·테니스·헬스 포함)",
        ActivityCode.SHOPPING to "쇼핑",
        ActivityCode.OTHER to "기타",
        ActivityCode.SIGHTSEEING to "관광·나들이",
        ActivityCode.OUTDOOR to "아웃도어(캠핑·바다·등산)",
        ActivityCode.TRANSIT to "이동"
    )

    val subjects: Map<PhotoSubject, String> = mapOf(
        PhotoSubject.PERSON to "인물",
        PhotoSubject.FOOD to "음식",
        PhotoSubject.LANDSCAPE to "풍경",
        PhotoSubject.OBJECT to "사물",
        PhotoSubject.ANIMAL to "동물",
        PhotoSubject.DOCUMENT to "문서·텍스트"
    )

    val people: Map<PeopleCount, String> = mapOf(
        PeopleCount.P0 to "0명",
        PeopleCount.P1 to "1명",
        PeopleCount.P2 to "2명",
        PeopleCount.P3_PLUS to "3명 이상(단체사진)"
    )
}
