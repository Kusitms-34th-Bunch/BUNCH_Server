package com.bunch.member

import com.bunch.member.domain.MemberBunchNameComparator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MemberBunchNameComparatorTest {
    @Test
    fun `compares each position with Korean English and numeric ordering`() {
        val names = listOf("10반", "3반", "BookClub", "퇴근런클럽", "여행가조", "반10", "반2", "반A", "반가")
        assertThat(names.sortedWith(MemberBunchNameComparator))
            .containsExactly("반가", "반A", "반2", "반10", "여행가조", "퇴근런클럽", "BookClub", "3반", "10반")
    }

    @Test
    fun `case numeric leading zeros and canonical Hangul compare equally before ID tie break`() {
        assertThat(MemberBunchNameComparator.compare("BookClub", "bookclub")).isZero()
        assertThat(MemberBunchNameComparator.compare("03반", "3반")).isZero()
        assertThat(MemberBunchNameComparator.compare("가", "가")).isZero()
        assertThat(MemberBunchNameComparator.compare("A", "a2")).isNegative()
        assertThat(MemberBunchNameComparator.compare("A9", "a1000000000000000000000000000000")).isNegative()
        assertThat(MemberBunchNameComparator.compare("A", "1")).isNegative()
        assertThat(MemberBunchNameComparator.compare("1", "📷")).isNegative()
    }
}
