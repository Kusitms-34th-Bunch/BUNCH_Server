package com.bunch.photo

import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.PhotoImage
import com.bunch.photo.service.PhotoUploadKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalTime

/**
 * The milliseconds of the capture time and the device model the app sends with a photo (recommended bundle combination
 * specification F7 and R1). Both are optional, a known value is never replaced, and a missing one is filled in by a
 * later upload of the same file.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhotoUploadMillisModelIntegrationTest : PhotoUploadTestSupport() {
    private val date = LocalDate.of(2026, 10, 19)
    private val time = LocalTime.of(21, 10, 5)

    @Test
    fun `a new photo keeps the milliseconds and the device model it was uploaded with`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(
            command(
                bunchId, ownerId,
                capturedDate = date,
                capturedTime = time,
                capturedMillis = 123,
                deviceModel = "iPhone15,2"
            ),
            image("with-millis")
        )

        assertThat(result.kind).isEqualTo(PhotoUploadKind.STORED)
        assertThat(millisOf(result.photoId)).isEqualTo(123)
        assertThat(modelOf(result.photoId)).isEqualTo("iPhone15,2")
    }

    @Test
    fun `a photo uploaded without them has neither, and zero milliseconds is a value of its own`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val without = uploads.upload(command(bunchId, ownerId, capturedDate = date, capturedTime = time), image("none"))
        val zero = uploads.upload(
            command(bunchId, ownerId, capturedDate = date, capturedTime = time, capturedMillis = 0),
            image("zero")
        )

        assertThat(millisOf(without.photoId)).isNull()
        assertThat(modelOf(without.photoId)).isNull()
        assertThat(millisOf(zero.photoId)).isZero()
    }

    @Test
    fun `milliseconds without a capture time are refused and nothing is stored`() {
        val (ownerId, bunchId) = bunchWithOwner()

        assertThatThrownBy {
            uploads.upload(command(bunchId, ownerId, capturedDate = date, capturedMillis = 5), image("no-time"))
        }.isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)

        assertThat(photoCount(bunchId)).isZero()
    }

    @Test
    fun `a merged upload fills what the first upload did not say`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("fill-by-merge")
        val first = uploads.upload(command(bunchId, ownerId, capturedDate = date, capturedTime = time), photo)

        val merged = uploads.upload(
            timed(bunchId, otherId, 7, "X"),
            photo
        )

        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(millisOf(first.photoId)).isEqualTo(7)
        assertThat(modelOf(first.photoId)).isEqualTo("X")
    }

    @Test
    fun `a file I uploaded again fills what my first upload did not say`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val photo = image("fill-by-retry")
        val first = uploads.upload(command(bunchId, ownerId, capturedDate = date, capturedTime = time), photo)

        val again = uploads.upload(
            timed(bunchId, ownerId, 9, "Y"),
            photo
        )

        assertThat(again.kind).isEqualTo(PhotoUploadKind.ALREADY_UPLOADED)
        assertThat(millisOf(first.photoId)).isEqualTo(9)
        assertThat(modelOf(first.photoId)).isEqualTo("Y")
    }

    @Test
    fun `a value that is already known is never replaced`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("keep-known")
        val first = uploads.upload(
            timed(bunchId, ownerId, 100, "A"),
            photo
        )

        uploads.upload(
            timed(bunchId, otherId, 200, "B"),
            photo
        )
        uploads.upload(
            timed(bunchId, ownerId, 300, "C"),
            photo
        )

        assertThat(millisOf(first.photoId)).isEqualTo(100)
        assertThat(modelOf(first.photoId)).isEqualTo("A")
    }

    @Test
    fun `a merged upload that says nothing changes nothing`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("say-nothing")
        val first = uploads.upload(
            timed(bunchId, ownerId, 42, "A"),
            photo
        )

        uploads.upload(command(bunchId, otherId), photo)

        assertThat(millisOf(first.photoId)).isEqualTo(42)
        assertThat(modelOf(first.photoId)).isEqualTo("A")
    }

    @Test
    fun `milliseconds are not filled into a photo that has no capture time`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("no-time-photo")
        val first = uploads.upload(command(bunchId, ownerId, capturedDate = date), photo)

        uploads.upload(
            command(bunchId, otherId, capturedDate = date, capturedTime = time, capturedMillis = 5),
            photo
        )

        assertThat(millisOf(first.photoId)).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 500, 999])
    fun `the database keeps milliseconds from 0 to 999`(millis: Int) {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("millis-$millis")))
        timeIt(photoId)

        jdbc.update("UPDATE photos SET captured_millis = ? WHERE id = ?", millis, photoId)

        assertThat(millisOf(photoId)).isEqualTo(millis)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 1000, 5000])
    fun `the database refuses milliseconds outside 0 to 999`(millis: Int) {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("bad-millis-$millis")))
        timeIt(photoId)

        assertThatThrownBy {
            jdbc.update("UPDATE photos SET captured_millis = ? WHERE id = ?", millis, photoId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the database refuses milliseconds on a photo without a capture time`() {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("millis-no-time")))

        assertThatThrownBy {
            jdbc.update("UPDATE photos SET captured_millis = 5 WHERE id = ?", photoId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a new photo keeps the size of the image the server read`() {
        val (ownerId, bunchId) = bunchWithOwner()

        val result = uploads.upload(command(bunchId, ownerId), image("with-size"))

        assertThat(sizeOf(result.photoId)).isEqualTo(1200 to 800)
    }

    @Test
    fun `a joining upload leaves the stored size alone`() {
        val (ownerId, bunchId) = bunchWithOwner()
        val otherId = joined(ownerId, bunchId)
        val photo = image("size-by-merge")
        val first = uploads.upload(command(bunchId, ownerId), photo)

        val merged = uploads.upload(command(bunchId, otherId), PhotoImage(photo.bytes, 640, 480))

        assertThat(merged.kind).isEqualTo(PhotoUploadKind.MERGED)
        assertThat(sizeOf(first.photoId)).isEqualTo(1200 to 800)
    }

    @Test
    fun `a photo stored without a size has none`() {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("old-photo")))

        assertThat(sizeOf(photoId)).isEqualTo(null to null)
    }

    @ParameterizedTest
    @ValueSource(strings = ["width = 100", "height = 100", "width = 0, height = 100", "width = 100, height = -1"])
    fun `the database refuses a size that is half given or not positive`(set: String) {
        val (_, bunchId) = bunchWithOwner()
        val photoId = insertPhoto(bunchId, hashOf(image("bad-size-$set")))

        assertThatThrownBy {
            jdbc.update("UPDATE photos SET $set WHERE id = ?", photoId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // An upload that has the date and the time of the capture, with the milliseconds and the model the app sent.
    private fun timed(bunchId: Long, memberId: Long, millis: Int, model: String) = command(
        bunchId, memberId,
        capturedDate = date,
        capturedTime = time,
        capturedMillis = millis,
        deviceModel = model
    )

    // Gives a photo that was inserted bare the date and the time of the capture, which the milliseconds need.
    private fun timeIt(photoId: Long) {
        jdbc.update(
            "UPDATE photos SET captured_date = ?, captured_time = ?, date_source = 'EXIF' WHERE id = ?",
            date,
            time,
            photoId
        )
    }

    private fun millisOf(photoId: Long): Int? = jdbc.queryForList(
        "SELECT captured_millis FROM photos WHERE id = ?",
        Int::class.javaObjectType,
        photoId
    ).single()

    private fun sizeOf(photoId: Long): Pair<Int?, Int?> = jdbc.queryForMap(
        "SELECT width, height FROM photos WHERE id = ?",
        photoId
    ).let { (it["width"] as Int?) to (it["height"] as Int?) }

    private fun modelOf(photoId: Long): String? = jdbc.queryForList(
        "SELECT device_model FROM photo_reco_feature WHERE photo_id = ?",
        String::class.java,
        photoId
    ).singleOrNull()
}
