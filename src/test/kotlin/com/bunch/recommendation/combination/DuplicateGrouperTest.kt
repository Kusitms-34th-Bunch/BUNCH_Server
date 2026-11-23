package com.bunch.recommendation.combination

import com.bunch.recommendation.combination.PreparationTestSupport.photo
import com.bunch.recommendation.combination.PreparationTestSupport.scene
import com.bunch.recommendation.domain.combination.CombinationSettings
import com.bunch.recommendation.domain.combination.DuplicateGroup
import com.bunch.recommendation.domain.combination.DuplicateGrouper
import com.bunch.recommendation.domain.combination.RecoPhoto
import com.bunch.recommendation.domain.combination.TimeNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.ZoneOffset

/**
 * Photos of one day that are the same shot are put into groups (recommended bundle specification 2-1, R1). The picture
 * itself is never compared: only the file (one photo already), the moment to the millisecond with the model and the
 * size, and a burst of one member with one device.
 */
class DuplicateGrouperTest {
    private val times = TimeNormalizer()
    private val settings = CombinationSettings()
    private val grouper = DuplicateGrouper(times, settings.burstMaxGap)

    private fun group(vararg photos: RecoPhoto): List<DuplicateGroup> = grouper.group(photos.map(times::start))

    private fun ids(groups: List<DuplicateGroup>): List<List<Long>> = groups.map { it.photoIds }

    // A shot with the milliseconds, the model and the size a phone records.
    private fun shot(
        id: Long,
        time: String,
        millis: Int? = 0,
        member: Long = 1,
        model: String? = "iPhone15,2",
        size: Pair<Int, Int>? = 4032 to 3024
    ): RecoPhoto = photo(id, time = time, members = listOf(member), millis = millis, model = model, size = size)

    @Test
    fun `the same original saved again is one group, whoever uploaded it`() {
        val groups = group(
            shot(1, "10:00:05", millis = 123, member = 1),
            shot(2, "10:00:05", millis = 123, member = 2)
        )

        assertThat(ids(groups)).containsExactly(listOf(1L, 2L))
        assertThat(groups.single().uploaderIds).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `two members with the same phone shooting in the same second are not a duplicate, case C8`() {
        val groups = group(
            shot(1, "10:00:05", millis = 120, member = 1),
            shot(2, "10:00:05", millis = 480, member = 2)
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L))
    }

    @Test
    fun `a time known only to the second is not the same moment`() {
        val groups = group(
            shot(1, "10:00:05", millis = null, member = 1),
            shot(2, "10:00:05", millis = null, member = 2)
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "iPhone15,2|4032|3024|true",
            "iPhone15,3|4032|3024|false",
            "iPhone15,2|3024|4032|false",
            "iPhone15,2|4032|3000|false"
        ]
    )
    fun `the same moment needs the same model and the same size`(
        model: String,
        width: Int,
        height: Int,
        together: Boolean
    ) {
        val groups = group(
            shot(1, "10:00:05", millis = 7, member = 1),
            shot(2, "10:00:05", millis = 7, member = 2, model = model, size = width to height)
        )

        assertThat(groups.size == 1).isEqualTo(together)
    }

    @Test
    fun `the same moment is not told when the model or the size is not known`() {
        val unknownModel = group(
            shot(1, "10:00:05", millis = 7, member = 1, model = null),
            shot(2, "10:00:05", millis = 7, member = 2, model = null)
        )
        val unknownSize = group(
            shot(3, "10:00:05", millis = 7, member = 1, size = null),
            shot(4, "10:00:05", millis = 7, member = 2, size = null)
        )

        assertThat(unknownModel).hasSize(2)
        assertThat(unknownSize).hasSize(2)
    }

    @ParameterizedTest
    @CsvSource("0,true", "1000,true", "2000,true", "2001,false", "5000,false")
    fun `a burst of one member with one device is one group within two seconds, case C2`(
        millisApart: Long,
        together: Boolean
    ) {
        val seconds = 5 + millisApart / 1000
        val millis = (millisApart % 1000).toInt()
        val groups = group(
            shot(1, "10:00:05", millis = 0),
            shot(2, "10:00:%02d".format(seconds), millis = millis)
        )

        assertThat(groups.size == 1).isEqualTo(together)
    }

    @Test
    fun `a burst needs the same member`() {
        val groups = group(
            shot(1, "10:00:05", millis = 100, member = 1),
            shot(2, "10:00:06", millis = 200, member = 2)
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L))
    }

    @Test
    fun `a burst needs the same model, and a model that is not known counts for nothing`() {
        val otherModel = group(
            shot(1, "10:00:05", millis = 100),
            shot(2, "10:00:06", millis = 200, model = "SM-S918N")
        )
        val unknownModel = group(
            shot(3, "10:00:05", millis = 100, model = null),
            shot(4, "10:00:06", millis = 200, model = null)
        )

        assertThat(otherModel).hasSize(2)
        assertThat(unknownModel).hasSize(2)
    }

    @Test
    fun `a burst is told by the second when the milliseconds are not known`() {
        val groups = group(
            shot(1, "10:00:05", millis = null),
            shot(2, "10:00:07", millis = null),
            shot(3, "10:00:10", millis = null)
        )

        assertThat(ids(groups)).containsExactly(listOf(1L, 2L), listOf(3L))
    }

    @Test
    fun `a burst of twenty shots is one group through the chain`() {
        val burst = (1..20).map { shot(it.toLong(), "10:00:%02d".format(it), millis = 0) }

        val groups = grouper.group(burst.map(times::start))

        assertThat(groups).hasSize(1)
        assertThat(groups.single().id).isEqualTo(1L)
    }

    @Test
    fun `a photo uploaded together shares a member with the burst of either uploader`() {
        val groups = group(
            photo(1, time = "10:00:05", members = listOf(1, 2), millis = 0, model = "iPhone15,2"),
            photo(2, time = "10:00:06", members = listOf(2), millis = 0, model = "iPhone15,2")
        )

        assertThat(ids(groups)).containsExactly(listOf(1L, 2L))
    }

    @Test
    fun `the same scene shot by other members is not a duplicate, the picture is not looked at`() {
        val groups = group(
            photo(1, time = "10:00:00", members = listOf(1), fingerprint = scene(0), model = "iPhone15,2"),
            photo(2, time = "10:00:05", members = listOf(2), fingerprint = scene(0), model = "iPhone15,2")
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L))
    }

    @Test
    fun `the uploaders of a group are all that uploaded any photo of it`() {
        val groups = group(
            photo(1, time = "10:00:05", members = listOf(1, 2), millis = 9, model = "iPhone15,2", size = 4032 to 3024),
            photo(2, time = "10:00:05", members = listOf(3), millis = 9, model = "iPhone15,2", size = 4032 to 3024)
        )

        assertThat(groups.single().uploaderIds).containsExactlyInAnyOrder(1L, 2L, 3L)
    }

    @Test
    fun `a photo without a time is a group of its own`() {
        val groups = group(
            shot(1, "10:00:05", millis = 0),
            photo(2, time = null, members = listOf(1), model = "iPhone15,2")
        )

        assertThat(ids(groups)).containsExactly(listOf(1L), listOf(2L))
    }

    @Test
    fun `the gap is measured on the absolute clock, whatever the local clocks say`() {
        val groups = group(
            photo(
                1, date = "2026-10-19", time = "09:00:00", offset = ZoneOffset.ofHours(9),
                millis = 0, model = "iPhone15,2"
            ),
            photo(
                2, date = "2026-10-18", time = "19:00:02", offset = ZoneOffset.ofHours(-5),
                millis = 0, model = "iPhone15,2"
            )
        )

        assertThat(groups).hasSize(1)
    }

    @Test
    fun `the groups come in the order of their first photo and the photos of a group in the order of the time`() {
        val groups = group(
            shot(9, "11:00:00", millis = 0),
            shot(7, "10:00:02", millis = 0),
            shot(8, "10:00:00", millis = 0),
            photo(5, time = null)
        )

        assertThat(ids(groups)).containsExactly(listOf(8L, 7L), listOf(9L), listOf(5L))
        assertThat(groups.map { it.id }).containsExactly(7L, 9L, 5L)
    }

    @Test
    fun `the same photos give the same groups whatever the order they come in`() {
        val photos = listOf(
            shot(1, "10:00:00", millis = 0, member = 1),
            shot(2, "10:00:01", millis = 500, member = 1),
            shot(3, "10:00:01", millis = 500, member = 2),
            shot(4, "10:05:00", millis = 0, member = 1),
            photo(5, time = null, members = listOf(3))
        )

        val reversed = grouper.group(photos.reversed().map(times::start))

        assertThat(reversed).isEqualTo(grouper.group(photos.map(times::start)))
    }
}
