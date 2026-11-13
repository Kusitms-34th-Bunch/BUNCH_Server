package com.bunch.photo

import com.bunch.bunch.domain.MembershipRole
import com.bunch.global.exception.ErrorCode
import com.bunch.global.image.ProfileImageStorage
import com.bunch.photo.service.PhotoChangedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class PhotoDateChangeIntegrationTest : PhotoHttpTestSupport() {
    @Autowired
    private lateinit var events: ApplicationEvents

    // Deleting a photo removes its image afterwards; the real storage would call Cloudinary.
    @MockitoBean
    private lateinit var storage: ProfileImageStorage

    @AfterEach
    fun cleanDateChangeFixtures() {
        jdbc.update("DELETE FROM photo_uploaders")
        jdbc.update("DELETE FROM photos")
        jdbc.update("DELETE FROM image_assets WHERE public_id LIKE 'test-http/%'")
    }

    // ---- What a change does ----

    @Test
    fun `the uploader changes the date and keeps the time and the source becomes the user`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", "21:40:12", uploaders = listOf(fixture.guest))

        setDate(fixture.guest, fixture.bunchId, listOf(photoId), "2026-10-03")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.updatedCount").value(1))

        assertThat(row(photoId)).isEqualTo(Row("2026-10-03", "21:40:12", "USER"))
    }

    @Test
    fun `a photo without a date gets a date without a time and moves from the undated list to the photo list`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, null, uploaders = listOf(fixture.guest))
        assertThat(listedIds(fixture, "undated")).containsExactly(photoId)
        assertThat(listedIds(fixture, "")).isEmpty()

        setDate(fixture.guest, fixture.bunchId, listOf(photoId), "2026-10-03").andExpect(status().isOk)

        assertThat(row(photoId)).isEqualTo(Row("2026-10-03", null, "USER"))
        assertThat(listedIds(fixture, "undated")).isEmpty()
        assertThat(listedIds(fixture, "")).containsExactly(photoId)
    }

    @Test
    fun `several photos change at once to the same date`() {
        val fixture = fixture()
        val first = photo(fixture.bunchId, "2026-09-28", "10:00:00", uploaders = listOf(fixture.guest))
        val second = photo(fixture.bunchId, null, uploaders = listOf(fixture.guest))
        val third = photo(fixture.bunchId, "2026-09-20", uploaders = listOf(fixture.guest))

        setDate(fixture.guest, fixture.bunchId, listOf(first, second, third), "2026-10-03")
            .andExpect(jsonPath("$.data.updatedCount").value(3))

        assertThat(row(first)).isEqualTo(Row("2026-10-03", "10:00:00", "USER"))
        assertThat(row(second)).isEqualTo(Row("2026-10-03", null, "USER"))
        assertThat(row(third)).isEqualTo(Row("2026-10-03", null, "USER"))
    }

    @Test
    fun `changing to the date the photo already has succeeds and can be repeated`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-10-03", "08:00:00", uploaders = listOf(fixture.guest))

        repeat(2) {
            setDate(fixture.guest, fixture.bunchId, listOf(photoId), "2026-10-03")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.updatedCount").value(1))
        }

        assertThat(row(photoId)).isEqualTo(Row("2026-10-03", "08:00:00", "USER"))
    }

    // 3.4.2: dragging changes only the date. The photo takes the place its shooting time gives it in that day,
    // and photos without a time stay last in the order they were uploaded.
    @Test
    fun `a photo moved to a day takes the place of its shooting time and photos without a time stay last`() {
        val fixture = fixture()
        val undated = photo(fixture.bunchId, null, uploaders = listOf(fixture.guest))
        val morning = photo(fixture.bunchId, "2026-10-03", "09:00:00")
        val evening = photo(fixture.bunchId, "2026-10-03", "21:00:00")
        val withoutTime = photo(fixture.bunchId, "2026-10-03", null)
        val elsewhere = photo(fixture.bunchId, "2026-09-20", "15:00:00", uploaders = listOf(fixture.guest))

        setDate(fixture.guest, fixture.bunchId, listOf(undated, elsewhere), "2026-10-03").andExpect(status().isOk)

        // The later time comes first (3.1.3). 15:00 lands between 21:00 and 09:00. Among the photos without a time
        // the one uploaded first comes first: the moved photo was uploaded before the one that already had a date.
        assertThat(listedIds(fixture, "")).containsExactly(evening, elsewhere, morning, undated, withoutTime)
    }

    @Test
    fun `today is allowed and tomorrow is rejected`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

        setDate(fixture.guest, fixture.bunchId, listOf(photoId), today.plusDays(1).toString())
            .expectError(ErrorCode.INVALID_INPUT)
        assertThat(row(photoId).date).isEqualTo("2026-09-28")

        setDate(fixture.guest, fixture.bunchId, listOf(photoId), today.toString()).andExpect(status().isOk)
        assertThat(row(photoId).date).isEqualTo(today.toString())
    }

    // ---- Who may change ----

    @Test
    fun `either uploader of a photo uploaded together changes the date of the photo itself`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest, fixture.third))

        setDate(fixture.third, fixture.bunchId, listOf(photoId), "2026-10-03").andExpect(status().isOk)
        assertThat(row(photoId).date).isEqualTo("2026-10-03")

        setDate(fixture.guest, fixture.bunchId, listOf(photoId), "2026-10-04").andExpect(status().isOk)
        assertThat(row(photoId).date).isEqualTo("2026-10-04")
    }

    @Test
    fun `an ordinary member who is mixed with a photo of someone else changes nothing`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val theirs = photo(fixture.bunchId, "2026-09-29", uploaders = listOf(fixture.third))

        setDate(fixture.guest, fixture.bunchId, listOf(mine, theirs), "2026-10-03")
            .expectError(ErrorCode.FORBIDDEN)

        assertThat(row(mine).date).isEqualTo("2026-09-28")
        assertThat(row(theirs).date).isEqualTo("2026-09-29")
        assertThat(changedEvents()).isEmpty()
    }

    @Test
    fun `an ordinary member may change a photo uploaded together when they are one of the uploaders`() {
        val fixture = fixture()
        val together = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.third, fixture.guest))

        setDate(fixture.guest, fixture.bunchId, listOf(together), "2026-10-03").andExpect(status().isOk)

        assertThat(row(together).date).isEqualTo("2026-10-03")
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MembershipRole::class, names = ["OWNER", "CO_OWNER"])
    fun `an administrator changes photos of other members and mixed selections`(role: MembershipRole) {
        val fixture = fixture()
        val administrator = administrator(fixture.owner, fixture.bunchId, role)
        val theirs = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val together = photo(fixture.bunchId, "2026-09-29", uploaders = listOf(fixture.guest, fixture.third))
        val own = photo(fixture.bunchId, "2026-09-30", uploaders = listOf(administrator))

        setDate(administrator, fixture.bunchId, listOf(theirs, together, own), "2026-10-03")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updatedCount").value(3))

        listOf(theirs, together, own).forEach { assertThat(row(it).date).isEqualTo("2026-10-03") }
    }

    // ---- Clearing the date (the undo of a drag) ----

    @Test
    fun `clearing returns a photo the user dated to the photos without a date`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, null, uploaders = listOf(fixture.guest))
        setDate(fixture.guest, fixture.bunchId, listOf(photoId), "2026-10-03").andExpect(status().isOk)

        clear(fixture.guest, fixture.bunchId, listOf(photoId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updatedCount").value(1))

        assertThat(row(photoId)).isEqualTo(Row(null, null, "UNKNOWN"))
        assertThat(listedIds(fixture, "undated")).containsExactly(photoId)
        assertThat(listedIds(fixture, "")).isEmpty()
    }

    @Test
    fun `clearing refuses photos with a shooting date or a time and changes nothing`() {
        val fixture = fixture()
        val userDated = photo(fixture.bunchId, "2026-10-03", dateSource = "USER", uploaders = listOf(fixture.guest))
        val exif = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val withTime = photo(
            fixture.bunchId,
            "2026-09-29",
            "10:00:00",
            dateSource = "USER",
            uploaders = listOf(fixture.guest)
        )

        clear(fixture.guest, fixture.bunchId, listOf(userDated, exif)).expectError(ErrorCode.INVALID_INPUT)
        clear(fixture.guest, fixture.bunchId, listOf(userDated, withTime)).expectError(ErrorCode.INVALID_INPUT)

        assertThat(row(userDated)).isEqualTo(Row("2026-10-03", null, "USER"))
        assertThat(row(exif)).isEqualTo(Row("2026-09-28", null, "EXIF"))
        assertThat(row(withTime)).isEqualTo(Row("2026-09-29", "10:00:00", "USER"))
    }

    @Test
    fun `an ordinary member cannot clear the photo of someone else`() {
        val fixture = fixture()
        val theirs = photo(fixture.bunchId, "2026-10-03", dateSource = "USER", uploaders = listOf(fixture.third))

        clear(fixture.guest, fixture.bunchId, listOf(theirs)).expectError(ErrorCode.FORBIDDEN)

        assertThat(row(theirs).date).isEqualTo("2026-10-03")
    }

    // ---- The request ----

    @Test
    fun `exactly one of the date and the clear flag is required`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val ids = ids(listOf(photoId))

        change(fixture.guest, fixture.bunchId, """{"photoIds":$ids}""").expectError(ErrorCode.INVALID_INPUT)
        change(fixture.guest, fixture.bunchId, """{"photoIds":$ids,"clearDate":false}""")
            .expectError(ErrorCode.INVALID_INPUT)
        change(fixture.guest, fixture.bunchId, """{"photoIds":$ids,"capturedDate":null}""")
            .expectError(ErrorCode.INVALID_INPUT)
        change(fixture.guest, fixture.bunchId, """{"photoIds":$ids,"capturedDate":"2026-10-03","clearDate":true}""")
            .expectError(ErrorCode.INVALID_INPUT)

        assertThat(row(photoId).date).isEqualTo("2026-09-28")
    }

    @Test
    fun `a body that cannot be read is invalid JSON`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val ids = ids(listOf(photoId))

        change(fixture.guest, fixture.bunchId, """{"photoIds":$ids,"capturedDate":"not-a-date"}""")
            .expectError(ErrorCode.INVALID_JSON)
        change(fixture.guest, fixture.bunchId, """{"capturedDate":"2026-10-03"}""")
            .expectError(ErrorCode.INVALID_JSON)
        change(fixture.guest, fixture.bunchId, "{").expectError(ErrorCode.INVALID_JSON)
    }

    @Test
    fun `the list of photo IDs is 1 to 50 distinct positive IDs`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))

        setDate(fixture.guest, fixture.bunchId, emptyList(), "2026-10-03").expectError(ErrorCode.INVALID_INPUT)
        setDate(fixture.guest, fixture.bunchId, (1L..51L).toList(), "2026-10-03")
            .expectError(ErrorCode.INVALID_INPUT)
        setDate(fixture.guest, fixture.bunchId, listOf(photoId, photoId), "2026-10-03")
            .expectError(ErrorCode.INVALID_INPUT)
        setDate(fixture.guest, fixture.bunchId, listOf(photoId, 0L), "2026-10-03")
            .expectError(ErrorCode.INVALID_INPUT)
        setDate(fixture.guest, fixture.bunchId, listOf(photoId, -4L), "2026-10-03")
            .expectError(ErrorCode.INVALID_INPUT)

        assertThat(row(photoId).date).isEqualTo("2026-09-28")
    }

    @Test
    fun `photos of another bunch and photos that are not ready reject the whole request`() {
        val fixture = fixture()
        val mine = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.owner))
        val otherBunch = create(member()).id
        val foreign = photo(otherBunch, "2026-09-29")
        val pending = photo(fixture.bunchId, "2026-09-30", status = "PENDING", uploaders = listOf(fixture.owner))

        setDate(fixture.owner, fixture.bunchId, listOf(mine, foreign), "2026-10-03")
            .expectError(ErrorCode.INVALID_INPUT)
        setDate(fixture.owner, fixture.bunchId, listOf(mine, pending), "2026-10-03")
            .expectError(ErrorCode.INVALID_INPUT)

        assertThat(row(mine).date).isEqualTo("2026-09-28")
        assertThat(row(foreign).date).isEqualTo("2026-09-29")
        assertThat(row(pending).date).isEqualTo("2026-09-30")
    }

    // 3.3.1: when a picked photo is already gone, nothing is run and the app drops it from the selection.
    @Test
    fun `a selection with a photo that is already gone changes nothing and names the gone photo`() {
        val fixture = fixture()
        val first = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val gone = photo(fixture.bunchId, "2026-09-29", uploaders = listOf(fixture.guest))
        val second = photo(fixture.bunchId, "2026-09-30", uploaders = listOf(fixture.guest))
        removeBehindTheScenes(gone)

        setDate(fixture.guest, fixture.bunchId, listOf(first, gone, second), "2026-10-03")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PHOTO_004"))
            .andExpect(jsonPath("$.error.detail.missingPhotoIds.length()").value(1))
            .andExpect(jsonPath("$.error.detail.missingPhotoIds[0]").value(gone))

        assertThat(row(first).date).isEqualTo("2026-09-28")
        assertThat(row(second).date).isEqualTo("2026-09-30")
        assertThat(changedEvents()).isEmpty()
    }

    @Test
    fun `non members members who left unfinished signups and unknown bunches are rejected`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val outsider = member()
        val left = joined(fixture.owner, fixture.bunchId)
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE member_id = ?", left)
        val unfinished = member(completed = false)

        setDate(outsider, fixture.bunchId, listOf(photoId), "2026-10-03").expectError(ErrorCode.FORBIDDEN)
        setDate(left, fixture.bunchId, listOf(photoId), "2026-10-03").expectError(ErrorCode.FORBIDDEN)
        setDate(unfinished, fixture.bunchId, listOf(photoId), "2026-10-03")
            .expectError(ErrorCode.SIGNUP_INCOMPLETE)
        setDate(fixture.guest, Long.MAX_VALUE, listOf(photoId), "2026-10-03")
            .expectError(ErrorCode.RESOURCE_NOT_FOUND)

        assertThat(row(photoId).date).isEqualTo("2026-09-28")
    }

    @Test
    fun `a request without a token is unauthorized`() {
        val fixture = fixture()

        mvc.perform(
            post("/api/v1/bunches/${fixture.bunchId}/photos/date")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"photoIds":[1],"capturedDate":"2026-10-03"}""")
        ).andExpect(status().isUnauthorized)
    }

    // ---- Telling the recommended bundles (4.2.9) ----

    @Test
    fun `the old dates and the new date are announced once each and no photo was added`() {
        val fixture = fixture()
        val first = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val second = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val third = photo(fixture.bunchId, "2026-09-20", uploaders = listOf(fixture.guest))
        val undated = photo(fixture.bunchId, null, uploaders = listOf(fixture.guest))

        setDate(fixture.guest, fixture.bunchId, listOf(first, second, third, undated), "2026-10-03")
            .andExpect(status().isOk)

        val changed = changedEvents()
        assertThat(changed.map { it.capturedDate })
            .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 3))
        assertThat(changed).allMatch { it.bunchId == fixture.bunchId && !it.photoAdded }
    }

    @Test
    fun `clearing announces the old date only`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-10-03", dateSource = "USER", uploaders = listOf(fixture.guest))

        clear(fixture.guest, fixture.bunchId, listOf(photoId)).andExpect(status().isOk)

        assertThat(changedEvents().map { it.capturedDate }).containsExactly(LocalDate.of(2026, 10, 3))
    }

    // ---- Several callers at once, on the real database ----

    @Test
    fun `two members changing the same photo at once both succeed and one date wins`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest, fixture.third))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val calls = listOf(fixture.guest to "2026-10-03", fixture.third to "2026-10-04").map { (who, date) ->
                val authorization = bearer(who)
                pool.submit(Callable {
                    start.await()
                    mvc.perform(
                        post("/api/v1/bunches/${fixture.bunchId}/photos/date")
                            .header(HttpHeaders.AUTHORIZATION, authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"photoIds":[$photoId],"capturedDate":"$date"}""")
                    ).andReturn().response.status
                })
            }
            start.countDown()
            assertThat(calls.map { it.get(30, TimeUnit.SECONDS) }).containsOnly(200)
        } finally {
            pool.shutdownNow()
        }

        assertThat(row(photoId).date).isIn("2026-10-03", "2026-10-04")
        assertThat(row(photoId).source).isEqualTo("USER")
    }

    @Test
    fun `a date change and a deletion of the same photo at once leave a consistent result`() {
        val fixture = fixture()
        val photoId = photo(fixture.bunchId, "2026-09-28", uploaders = listOf(fixture.guest))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val authorization = bearer(fixture.guest)
            val change = pool.submit(Callable {
                start.await()
                mvc.perform(
                    post("/api/v1/bunches/${fixture.bunchId}/photos/date")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"photoIds":[$photoId],"capturedDate":"2026-10-03"}""")
                ).andReturn().response.status
            })
            val delete = pool.submit(Callable {
                start.await()
                mvc.perform(
                    post("/api/v1/bunches/${fixture.bunchId}/photos/delete")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"photoIds":[$photoId]}""")
                ).andReturn().response.status
            })
            start.countDown()
            // Either the change ran first and the deletion removed the photo, or the deletion ran first and the
            // change found the photo gone (409). A change never succeeds on a photo that is gone.
            assertThat(delete.get(30, TimeUnit.SECONDS)).isEqualTo(200)
            assertThat(change.get(30, TimeUnit.SECONDS)).isIn(200, 409)
        } finally {
            pool.shutdownNow()
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM photos WHERE id = ?", Long::class.java, photoId))
            .isEqualTo(0L)
    }

    // ---- Helpers ----

    private data class Fixture(val bunchId: Long, val owner: Long, val guest: Long, val third: Long)

    /** A bunch whose administrator is [Fixture.owner] and whose other two members are ordinary members. */
    private fun fixture(): Fixture {
        val (owner, bunchId) = bunchWithOwner()
        return Fixture(bunchId, owner, joined(owner, bunchId), joined(owner, bunchId))
    }

    private data class Row(val date: String?, val time: String?, val source: String)

    private fun row(photoId: Long): Row {
        val columns = jdbc.queryForMap(
            "SELECT captured_date, captured_time, date_source FROM photos WHERE id = ?",
            photoId
        )
        return Row(
            columns["captured_date"]?.toString(),
            columns["captured_time"]?.toString(),
            columns["date_source"].toString()
        )
    }

    private fun photo(
        bunchId: Long,
        date: String?,
        time: String? = null,
        dateSource: String = if (date == null) "UNKNOWN" else "EXIF",
        status: String = "READY",
        uploaders: List<Long> = emptyList()
    ): Long = insertPhoto(bunchId, date, time, dateSource, status, uploaders)

    /** Another member deleted the photo after the app had picked it. */
    private fun removeBehindTheScenes(photoId: Long) {
        jdbc.update("DELETE FROM photo_uploaders WHERE photo_id = ?", photoId)
        jdbc.update("DELETE FROM photos WHERE id = ?", photoId)
    }

    private fun changedEvents(): List<PhotoChangedEvent> = events.stream(PhotoChangedEvent::class.java).toList()

    private fun ids(photoIds: List<Long>) = photoIds.joinToString(prefix = "[", postfix = "]")

    private fun setDate(memberId: Long, bunchId: Long, photoIds: List<Long>, date: String): ResultActions =
        change(memberId, bunchId, """{"photoIds":${ids(photoIds)},"capturedDate":"$date"}""")

    private fun clear(memberId: Long, bunchId: Long, photoIds: List<Long>): ResultActions =
        change(memberId, bunchId, """{"photoIds":${ids(photoIds)},"clearDate":true}""")

    private fun change(memberId: Long, bunchId: Long, body: String): ResultActions =
        mvc.perform(
            post("/api/v1/bunches/$bunchId/photos/date")
                .header(HttpHeaders.AUTHORIZATION, bearer(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )

    /** The photo IDs the photo box list (`""`) or the list of photos without a date (`"undated"`) shows. */
    private fun listedIds(fixture: Fixture, path: String): List<Long> =
        mvc.perform(
            get("/api/v1/bunches/${fixture.bunchId}/photos/$path".trimEnd('/'))
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.guest))
        ).andExpect(status().isOk).andReturn().data().path("photos").map { it.path("photoId").asLong() }
}
