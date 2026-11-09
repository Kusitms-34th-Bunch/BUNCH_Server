package com.bunch.member.domain

import java.math.BigInteger
import java.text.Normalizer

object MemberBunchNameComparator : Comparator<String> {
    override fun compare(first: String, second: String): Int {
        val left = Normalizer.normalize(first, Normalizer.Form.NFC).codePoints().toArray()
        val right = Normalizer.normalize(second, Normalizer.Form.NFC).codePoints().toArray()
        var i = 0
        var j = 0
        while (i < left.size && j < right.size) {
            val category = category(left[i]).compareTo(category(right[j]))
            if (category != 0) return category

            if (left[i] in '0'.code..'9'.code && right[j] in '0'.code..'9'.code) {
                val leftEnd = digitEnd(left, i)
                val rightEnd = digitEnd(right, j)
                val number = number(left, i, leftEnd).compareTo(number(right, j, rightEnd))
                if (number != 0) return number
                i = leftEnd
                j = rightEnd
            } else {
                val letter = Character.toLowerCase(left[i]).compareTo(Character.toLowerCase(right[j]))
                if (letter != 0) return letter
                i++
                j++
            }
        }
        return (left.size - i).compareTo(right.size - j)
    }

    private fun category(codePoint: Int): Int = when {
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL -> 0
        codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code -> 1
        codePoint in '0'.code..'9'.code -> 2
        else -> 3
    }

    private fun digitEnd(points: IntArray, start: Int): Int {
        var end = start
        while (end < points.size && points[end] in '0'.code..'9'.code) end++
        return end
    }

    private fun number(points: IntArray, start: Int, end: Int): BigInteger =
        BigInteger(String(points, start, end - start))
}
