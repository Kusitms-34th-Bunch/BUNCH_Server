package com.bunch.recommendation.domain

import java.time.LocalDate

/**
 * Tells whether a date is a special day. Taking the holidays from a public calendar later only needs another one.
 *
 * [DB 연동 지점] 공휴일 외부 연동(별도 이슈)에서 공공데이터포털에서 받아 저장한 값(DB)을 읽는 구현으로 바꾼다.
 */
interface SpecialDayCalendar {
    fun specialDay(date: LocalDate): SpecialDay?
}
