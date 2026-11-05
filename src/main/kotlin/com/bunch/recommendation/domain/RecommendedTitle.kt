package com.bunch.recommendation.domain

/**
 * A made title. [sentenceId] is the sentence it came from and is empty for a title that did not come from one.
 *
 * [DB 연동 지점 #70] title은 bundle_candidates.title에, sentenceId는 bundle_candidates.title_sentence_key에 저장한다.
 * 저장하는 일은 이 클래스가 아니라 #70(재계산·저장)의 몫이고, 한 번 저장한 제목은 바꾸지 않는다.
 */
data class RecommendedTitle(
    val title: String,
    val sentenceId: String?
)
