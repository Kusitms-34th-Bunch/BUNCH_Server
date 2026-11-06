package com.bunch.recommendation.domain

import java.time.LocalDate
import java.time.LocalTime

/**
 * What a title is made from. [startDate] is the day the special day and the weekday are judged by, also for a cluster
 * over midnight. [photoTimes] are the shooting times of the photos that have one. [uploaderNicknames] start with the
 * member with the most photos. [earliestPhotoDate] and [previousEndDate] are taken from all photos with a date,
 * also the ones a bundle uses. [visibleTitles] are the titles already in the list and [recentSentenceIds] the sentences
 * used lately, the newest first.
 *
 * [DB 연동 지점 #70] 이 값들은 #70(재계산)이 DB에서 모아 넘긴다. 이 클래스와 TitleGenerator는 DB를 읽지 않는다.
 * - uploaderNicknames: photo_uploaders와 members.nickname에서 묶음 사진을 올린 멤버(사진 많은 순)
 * - bunchMemberCount: bunch_memberships의 활성 멤버 수
 * - earliestPhotoDate, previousEndDate: photos.captured_date (초안·발행 번들에 쓰인 사진 포함)
 * - visibleTitles, recentSentenceIds: bundle_candidates의 title, title_sentence_key (후보 테이블은 #70에서 만든다)
 */
data class TitleContext(
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val photoTimes: List<LocalTime>,
    val uploaderNicknames: List<String>,
    val bunchMemberCount: Int,
    val earliestPhotoDate: LocalDate,
    val previousEndDate: LocalDate?,
    val visibleTitles: Set<String> = emptySet(),
    val recentSentenceIds: List<String> = emptyList()
)
