package com.bunch.recommendation.domain

/**
 * Where the title sentences come from. Moving them to a database later only needs another source.
 *
 * [DB 연동 지점] 지금은 코드의 문장(BuiltInTitleSentences)을 읽는다. 문장을 DB에서 관리하게 되면(별도 이슈
 * 「추천 번들 제목 문장 관리」) 이 인터페이스를 DB에서 읽는 구현으로 바꾸고, 제목 생성기(TitleGenerator)는 고치지 않는다.
 */
interface TitleSentenceSource {
    fun sentences(): List<TitleSentence>
}
