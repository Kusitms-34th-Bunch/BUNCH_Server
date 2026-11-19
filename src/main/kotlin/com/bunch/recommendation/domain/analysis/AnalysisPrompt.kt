package com.bunch.recommendation.domain.analysis

/**
 * What the model is told (recommended bundle specification 4-3, "the instructions of the request"). The code lists are
 * made from [AnalysisCodeNames], so the model is only ever offered the codes the combination knows.
 *
 * The instructions only ask what the photo shows. They never ask who the people are: the people are counted and
 * nothing else is said of them (1.7).
 */
object AnalysisPrompt {
    /** The scenes that do not count as a clue (4-3). */
    private val NOT_CLUES = listOf(
        "카페의 조각 케이크",
        "식당 옆 테이블의 케이크",
        "졸업 시즌 캠퍼스에 찍힌 다른 사람",
        "웨딩 촬영 스튜디오, 식장 앞을 지나며 찍은 사진",
        "거리 공연, 라이브 카페의 작은 무대",
        "TV 화면 속 공연·경기",
        "노래방 조명",
        "평소 러닝·운동",
        "출퇴근 기차역, 공항 배웅",
        "고깃집 불판",
        "고궁 한복 체험",
        "동네 놀이터, 쇼핑몰 안의 작은 회전목마"
    )

    val USER_MESSAGE = "이 사진을 분석해 JSON으로만 답한다."

    val SYSTEM: String = buildString {
        appendLine("사진 한 장을 보고, 보이는 것만 아래 코드 목록 안의 코드로 답한다. JSON 외의 글은 쓰지 않는다.")
        appendLine("사진 속 사람이 누구인지 알아보려 하지 않는다. 사람은 수만 센다.")
        appendLine()
        appendLine("[clues] 사진에 보이는 단서 코드와 확신도(conf, 0~1). 코드 목록에 있는 단서만 답하고, 보이지 않으면 넣지 않는다.")
        appendLine("한 장을 특정 이벤트로 분류하지 않는다. 보이는 단서만 뽑는다.")
        appendLine("아래 장면은 단서로 잡지 않는다. 트리 자체는 XMAS_TREE로 잡되, 이런 장면이면 확신도를 낮게 준다.")
        NOT_CLUES.forEach { appendLine("- $it") }
        appendLine()
        appendLine("이벤트 단서 코드:")
        AnalysisCodeNames.eventClues.forEach { (code, name) -> appendLine("- ${code.name}: $name") }
        appendLine()
        appendLine("민감 단서 코드(보이면 같은 clues 목록에 넣는다):")
        AnalysisCodeNames.sensitiveClues.forEach { (code, name) -> appendLine("- ${code.name}: $name") }
        appendLine()
        appendLine("[activity] 아래 중 가장 맞는 활동 1개와 확신도(conf, 0~1).")
        AnalysisCodeNames.activities.forEach { (code, name) -> appendLine("- ${code.name}: $name") }
        appendLine()
        appendLine("[subject] 사진의 주인공 1개.")
        AnalysisCodeNames.subjects.forEach { (code, name) -> appendLine("- ${code.name}: $name") }
        appendLine()
        appendLine("[people] 사진의 주인공으로 찍힌 사람 수만 센다. 배경에 작게 찍힌 사람은 세지 않는다.")
        AnalysisCodeNames.people.forEach { (code, name) -> appendLine("- ${code.name}: $name") }
        appendLine()
        appendLine("확신도는 확실할수록 1에 가깝게, 애매하면 낮게 준다.")
    }
}
