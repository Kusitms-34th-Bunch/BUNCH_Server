package com.bunch.bundle

import com.bunch.bunch.BunchIntegrationSupport
import com.bunch.bundle.domain.Bundle
import com.bunch.bundle.domain.BundleLocationRepository
import com.bunch.bundle.domain.BundleParticipant
import com.bunch.bundle.domain.BundleParticipantRepository
import com.bunch.bundle.domain.BundleRepository
import com.bunch.bundle.dto.BundleActor
import com.bunch.bundle.domain.BundleLocationIntent
import com.bunch.bundle.dto.BundleLocationIntentPlace
import com.bunch.bundle.dto.BundleLocationIntentRequest
import com.bunch.bundle.dto.BundleLocationIntentResponse
import com.bunch.bundle.dto.BundleLocationsRemovedEvent
import com.bunch.bundle.service.BundleLocationService
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import com.bunch.global.redis.RefreshTokenRepository
import com.bunch.global.security.TokenProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BundleLocationIntentIntegrationTest.EventConfiguration::class)
class BundleLocationIntentIntegrationTest : BunchIntegrationSupport() {
    @Autowired
    private lateinit var locations: BundleLocationService
    @Autowired
    private lateinit var rows: BundleLocationRepository
    @Autowired
    private lateinit var bundles: BundleRepository
    @Autowired
    private lateinit var participants: BundleParticipantRepository
    @Autowired
    private lateinit var transactions: PlatformTransactionManager
    @Autowired
    private lateinit var rejection: RemovalRejection
    @Autowired
    private lateinit var mvc: MockMvc
    @Autowired
    private lateinit var mapper: ObjectMapper
    @Autowired
    private lateinit var tokens: TokenProvider
    @Autowired
    private lateinit var sessions: RefreshTokenRepository

    private val credentials = mutableListOf<Pair<Long, String>>()

    @BeforeEach
    fun resetRejection() {
        rejection.reject = false
    }

    @AfterEach
    fun cleanLocations() {
        rejection.reject = false
        jdbc.execute("DROP TRIGGER IF EXISTS location_intent_reject_pin ON pinned_places")
        jdbc.execute("DROP FUNCTION IF EXISTS location_intent_reject_pin()")
        jdbc.update("DELETE FROM pinned_places")
        jdbc.update("DELETE FROM bundles")
        credentials.forEach { (id, sid) -> sessions.delete(sid, id) }
    }

    @Test
    fun `add preserves selection order and row IDs but rejects an overflowing batch atomically`() {
        val f = fixture()
        val initial = add(f, "a")
        val added = add(f, "c", "b")
        assertThat(added.acceptedCount).isEqualTo(2)
        assertThat(added.locations.map { it.externalId }).containsExactly("a", "c", "b")
        assertThat(added.locations.first().id).isEqualTo(initial.locations.single().id)
        assertThat(added.locationVersion).isEqualTo(initial.locationVersion + 1)
        assertUnchangedConflict(f, "LOCATION_CAPACITY_EXCEEDED") { add(f, "d", "e") }

        val oneSlot = fixture()
        add(oneSlot, "a", "b")
        assertUnchangedConflict(oneSlot, "LOCATION_CAPACITY_EXCEEDED") { add(oneSlot, "c", "d") }
    }

    @Test
    fun `normalized duplicate against existing row or within batch rejects all additions`() {
        val f = fixture()
        add(f, "a")
        assertUnchangedConflict(f, "LOCATION_DUPLICATE") {
            apply(f, BundleLocationIntentRequest(BundleLocationIntent.ADD, locations = listOf(
                place("b"), place("a").copy(provider = " KAKAO ", externalId = " a ")
            )))
        }
        val empty = fixture()
        assertUnchangedConflict(empty, "LOCATION_DUPLICATE") {
            apply(empty, BundleLocationIntentRequest(BundleLocationIntent.ADD, locations = listOf(
                place("a"), place("a").copy(provider = " KAKAO ", externalId = " a "), place("b")
            )))
        }
    }

    @Test
    fun `concurrent additions serialize and reject the losing batch without partial acceptance`() {
        val f = fixture()
        add(f, "seed")
        val results = concurrently(listOf(
            { add(f, "a1", "a2") },
            { add(f.copy(actor = f.guest), "b1", "b2") }
        ))
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertConflict(results.single { it.isFailure }.exceptionOrNull(), "LOCATION_CAPACITY_EXCEEDED")
        val winner = results.single { it.isSuccess }.getOrThrow()
        assertThat(winner.acceptedCount).isEqualTo(2)
        val state = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        assertThat(state.locations).isEqualTo(winner.locations)
        assertThat(state.locations.map { it.externalId }).satisfiesAnyOf(
            { assertThat(it).containsExactly("seed", "a1", "a2") },
            { assertThat(it).containsExactly("seed", "b1", "b2") }
        )
        assertThat(state.locationVersion).isEqualTo(2)
    }

    @Test
    fun `concurrent normalized duplicate additions preserve the first stored place`() {
        val f = fixture()
        val results = concurrently(listOf(
            { add(f, "same") },
            { apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
                BundleLocationIntent.ADD,
                locations = listOf(place("same").copy(
                    provider = " KAKAO ", externalId = " same ", name = "other name"
                ))
            )) }
        ))
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertConflict(results.single { it.isFailure }.exceptionOrNull(), "LOCATION_DUPLICATE")
        val winner = results.single { it.isSuccess }.getOrThrow()
        val state = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        assertThat(state.locations).isEqualTo(winner.locations)
        assertThat(state.locationVersion).isEqualTo(1)
    }

    @Test
    fun `concurrent remove and move of same target always leave it deleted`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val a = initial.locations[0].id
        val b = initial.locations[1].id
        concurrently(listOf(
            { apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = a)) },
            { apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
                BundleLocationIntent.MOVE, targetLocationId = a, previousLocationId = b
            )) }
        )).forEach { result ->
            result.exceptionOrNull()?.let { assertConflict(it, "LOCATION_REMOVED") }
        }
        val final = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        assertThat(final.locations.map { it.externalId }).containsExactly("b", "c")
        assertThat(final.locations.map { it.position }).containsExactly(0, 1)
    }

    @Test
    fun `move reapplies latest neighbors preserves IDs and tolerates deleted same bundle IDs`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val a = initial.locations[0].id
        val b = initial.locations[1].id
        val c = initial.locations[2].id
        val moved = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.MOVE, targetLocationId = c, nextLocationId = a
        ))
        assertThat(moved.locations.map { it.id }).containsExactly(c, a, b)
        apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = a))
        val rebased = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.MOVE, targetLocationId = c, previousLocationId = a, nextLocationId = b
        ))
        assertThat(rebased.locations.map { it.id }).containsExactly(c, b)
        assertThat(rebased.changed).isFalse()
        assertUnchangedConflict(f, "LOCATION_REMOVED") {
            apply(f, BundleLocationIntentRequest(BundleLocationIntent.MOVE, targetLocationId = a))
        }
    }

    @Test
    fun `replacement of deleted target appends if room and rejects duplicates or capacity overflow`() {
        val f = fixture()
        val target = add(f, "a", "b").locations.first().id
        apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = target))
        val replacement = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("c")
        ))
        assertThat(replacement.locations.map { it.externalId }).containsExactly("b", "c")
        assertThat(replacement.acceptedCount).isEqualTo(1)
        assertUnchangedConflict(f, "LOCATION_DUPLICATE") {
            apply(f, BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = target, location = place("c")
            ))
        }
        add(f, "d")
        assertUnchangedConflict(f, "LOCATION_CAPACITY_EXCEEDED") {
            apply(f, BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = target, location = place("e")
            ))
        }
    }

    @Test
    fun `successive replacements keep the same row ID and last processed place wins`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val target = initial.locations[1].id
        val first = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("x")
        ))
        val second = apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("y")
        ))
        assertThat(first.locations.map { it.id }).isEqualTo(initial.locations.map { it.id })
        assertThat(first.locations.map { it.externalId }).containsExactly("a", "x", "c")
        assertThat(second.locations.map { it.id }).isEqualTo(initial.locations.map { it.id })
        assertThat(second.locations.map { it.externalId }).containsExactly("a", "y", "c")
        assertThat(second.locationVersion).isEqualTo(initial.locationVersion + 2)
        assertThat(second.revision).isEqualTo(initial.revision + 2)
        assertThat(removedIdCount(f)).isZero()
    }

    @Test
    fun `concurrent same row replacements keep one row with the last committed response`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val target = initial.locations[1].id
        val results = concurrently(listOf(
            { apply(f, BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = target, location = place("x")
            )) },
            { apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = target, location = place("y")
            )) }
        )).map { it.getOrThrow() }
        val latest = results.maxBy { it.revision }
        val state = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        assertThat(results.map { it.revision }).containsExactlyInAnyOrder(initial.revision + 1, initial.revision + 2)
        assertThat(state.locations).isEqualTo(latest.locations)
        assertThat(state.locations.map { it.id }).isEqualTo(initial.locations.map { it.id })
        assertThat(state.locations[1].externalId).isIn("x", "y")
        assertThat(removedIdCount(f)).isZero()
    }

    @Test
    fun `replace followed by removal deletes the same row and repeated removal is a no op`() {
        val f = fixture()
        val initial = add(f, "a", "b")
        val target = initial.locations.first().id
        apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("x")
        ))
        val deleted = apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
            BundleLocationIntent.REMOVE, targetLocationId = target
        ))
        assertThat(deleted.locations.map { it.externalId }).containsExactly("b")
        assertThat(deleted.changed).isTrue()
        assertThat(removedIdCount(f)).isEqualTo(1)
        val repeated = apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = target))
        assertThat(repeated.changed).isFalse()
        assertThat(repeated.acceptedCount).isZero()
        assertThat(repeated.locations).isEqualTo(deleted.locations)
        assertThat(repeated.revision).isEqualTo(deleted.revision)
        assertThat(repeated.locationVersion).isEqualTo(deleted.locationVersion)
        assertThat(removedIdCount(f)).isEqualTo(1)
    }

    @Test
    fun `deleted row replacement appends a new ID and leaves the deleted original absent`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val target = initial.locations[1].id
        apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = target))
        val replaced = apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("x")
        ))
        assertThat(replaced.locations.map { it.externalId }).containsExactly("a", "c", "x")
        assertThat(replaced.locations.take(2).map { it.id })
            .containsExactly(initial.locations[0].id, initial.locations[2].id)
        assertThat(replaced.locations.last().id).isNotEqualTo(target)
        assertThat(rows.findById(target)).isEmpty()
        assertThat(removedIdCount(f)).isEqualTo(1)
    }

    @Test
    fun `replacement clears only old source pins without tombstoning the live row`() {
        val f = fixture()
        val initial = add(f, "a", "b")
        val target = initial.locations.first().id
        val retained = initial.locations.last().id
        pin(f, target, "a")
        pin(f, retained, "b")
        val replaced = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("x")
        ))
        assertThat(replaced.locations.first().id).isEqualTo(target)
        assertThat(replaced.locations.first().externalId).isEqualTo("x")
        assertThat(pinSources(f)).containsExactly(retained)
        assertThat(removedIdCount(f)).isZero()
        // The new place can be pinned using the same source ID and is cleaned on a later replacement.
        pin(f, target, "x")
        val again = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("y")
        ))
        assertThat(again.locations.first().id).isEqualTo(target)
        assertThat(pinSources(f)).containsExactly(retained)
        assertThat(removedIdCount(f)).isZero()
    }

    @Test
    fun `drop of a replaced row moves its current place using the original row ID`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val target = initial.locations.first().id
        apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
            BundleLocationIntent.REPLACE, targetLocationId = target, location = place("x")
        ))
        val dropped = apply(f, BundleLocationIntentRequest(
            BundleLocationIntent.MOVE, targetLocationId = target, previousLocationId = initial.locations.last().id
        ))
        assertThat(dropped.locations.map { it.externalId }).containsExactly("b", "c", "x")
        assertThat(dropped.locations.map { it.id })
            .containsExactly(initial.locations[1].id, initial.locations[2].id, target)
        assertThat(dropped.changed).isTrue()
    }

    @Test
    fun `concurrent replacements racing for one normalized key retain one winner and the untouched loser`() {
        val f = fixture()
        val initial = add(f, "a", "b")
        val results = concurrently(listOf(
            { apply(f, BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = initial.locations[0].id, location = place("x")
            )) },
            { apply(f.copy(actor = f.guest), BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = initial.locations[1].id,
                location = place("x").copy(provider = " KAKAO ", externalId = " x ")
            )) }
        ))
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertConflict(results.single { it.isFailure }.exceptionOrNull(), "LOCATION_DUPLICATE")
        val winner = results.single { it.isSuccess }.getOrThrow()
        val state = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        assertThat(state.locations).isEqualTo(winner.locations)
        assertThat(state.locations.map { it.id }).isEqualTo(initial.locations.map { it.id })
        assertThat(state.locations.count { it.externalId == "x" }).isEqualTo(1)
        assertThat(state.locationVersion).isEqualTo(initial.locationVersion + 1)
        assertThat(removedIdCount(f)).isZero()
    }

    @Test
    fun `replacement with an existing location keeps all locations pins and bundle metadata unchanged`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val a = initial.locations[0].id
        val b = initial.locations[1].id
        pin(f, a, "a")
        pin(f, b, "b")
        assertReplacementRejected(f, a, place("b"))
        assertThat(pinSources(f)).containsExactly(a, b)
    }

    @Test
    fun `replacement with a normalized existing key ignores incoming metadata and keeps both pins`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val a = initial.locations[0].id
        val b = initial.locations[1].id
        pin(f, a, "a")
        pin(f, b, "b")
        assertReplacementRejected(f, a, place("b").copy(
            provider = " KAKAO ",
            externalId = " b ",
            name = "changed duplicate name",
            address = "changed duplicate address"
        ))
        assertThat(pinSources(f)).containsExactly(a, b)
    }

    @Test
    fun `replacement with the targets own normalized key preserves its stored metadata`() {
        val f = fixture()
        val initial = add(f, "a", "b", "c")
        val a = initial.locations[0].id
        pin(f, a, "a")
        assertReplacementRejected(f, a, place("a").copy(
            provider = " KAKAO ",
            externalId = " a ",
            name = "changed target name",
            address = "changed target address"
        ))
        assertThat(pinSources(f)).containsExactly(a)
    }

    @Test
    fun `unknown foreign and deleted foreign IDs cannot mutate target or anchors`() {
        val f = fixture()
        val target = add(f, "a", "b").locations.first().id
        val foreign = fixture()
        val foreignId = add(foreign, "foreign").locations.single().id
        fun reject(id: Long) {
            listOf(
                BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = id),
                BundleLocationIntentRequest(BundleLocationIntent.REPLACE, targetLocationId = id, location = place("x")),
                BundleLocationIntentRequest(BundleLocationIntent.MOVE, targetLocationId = id),
                BundleLocationIntentRequest(
                    BundleLocationIntent.MOVE, targetLocationId = target, previousLocationId = id
                ),
                BundleLocationIntentRequest(BundleLocationIntent.MOVE, targetLocationId = target, nextLocationId = id)
            ).forEach { request -> assertError(ErrorCode.RESOURCE_NOT_FOUND) { apply(f, request) } }
        }
        reject(Long.MAX_VALUE)
        reject(foreignId)
        apply(foreign, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = foreignId))
        reject(foreignId)
        // Also reject another draft's deleted ID within the same bunch.
        val sameBunch = newDraft(f.actor, f.guest, f.bunchId)
        val sameBunchId = add(sameBunch, "same-bunch").locations.single().id
        apply(sameBunch, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = sameBunchId))
        reject(sameBunchId)
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId).locations.map { it.externalId })
            .containsExactly("a", "b")
    }

    @Test
    fun `invalid shapes and more than three selections reject before partial writes`() {
        val f = fixture()
        listOf(
            BundleLocationIntentRequest(BundleLocationIntent.ADD),
            BundleLocationIntentRequest(BundleLocationIntent.ADD, locations = (1..4).map { place("$it") }),
            BundleLocationIntentRequest(BundleLocationIntent.ADD, locations = listOf(place("a")), targetLocationId = 1),
            BundleLocationIntentRequest(BundleLocationIntent.REMOVE),
            BundleLocationIntentRequest(BundleLocationIntent.REPLACE, targetLocationId = 1),
            BundleLocationIntentRequest(BundleLocationIntent.MOVE, targetLocationId = -1),
            BundleLocationIntentRequest(BundleLocationIntent.MOVE, targetLocationId = 1, nextLocationId = 1),
            BundleLocationIntentRequest(BundleLocationIntent.MOVE, targetLocationId = 1,
                previousLocationId = 2, nextLocationId = 2),
            BundleLocationIntentRequest(
                BundleLocationIntent.ADD, locations = listOf(place("a"), place("bad").copy(name = " "))
            )
        ).forEach { request -> assertError(ErrorCode.INVALID_INPUT) { apply(f, request) } }
        assertThat(rows.findAllByBundleIdOrderByPositionAscIdAsc(f.bundleId)).isEmpty()
        assertThat(bundles.findById(f.bundleId).orElseThrow().revision).isZero()
    }

    @Test
    fun `nonparticipants departed members wrong path and published drafts reject intents`() {
        val f = fixture()
        val outsider = member()
        join(outsider, issue(f.actor.memberId, f.bunchId))
        assertError(ErrorCode.FORBIDDEN) { add(f.copy(actor = BundleActor(outsider, "other")), "a") }
        val foreignBunch = create(f.actor.memberId).id
        assertError(ErrorCode.RESOURCE_NOT_FOUND) { add(f.copy(bunchId = foreignBunch), "a") }
        jdbc.update("UPDATE bunch_memberships SET status = 'LEFT' WHERE bunch_id = ? AND member_id = ?",
            f.bunchId, f.guest.memberId)
        assertError(ErrorCode.FORBIDDEN) { add(f.copy(actor = f.guest), "a") }
        // Creating the foreign bunch switched the owner's current-bunch feed context.
        bunches.updateLastUsed(f.actor.memberId, f.bunchId)
        jdbc.update("UPDATE bundles SET status = 'PUBLISHED', published_at = updated_at WHERE id = ?", f.bundleId)
        assertError(ErrorCode.BUNDLE_STATE_CONFLICT) { add(f, "a") }
    }

    @Test
    fun `pin cleanup and removed ID provenance roll back atomically with failed intent`() {
        val f = fixture()
        val before = add(f, "a")
        val source = before.locations.single().id
        pin(f, source, "a")
        rejection.reject = true
        assertThatThrownBy {
            apply(f, BundleLocationIntentRequest(BundleLocationIntent.REPLACE,
                targetLocationId = source, location = place("b")))
        }.isInstanceOf(IllegalStateException::class.java)
        rejection.reject = false
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId).locations).isEqualTo(before.locations)
        assertThat(pinSources(f)).containsExactly(source)
        assertThat(removedIdCount(f)).isZero()
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = source))
            assertThat(pinSources(f)).isEmpty()
            tx.setRollbackOnly()
        }
        assertThat(pinSources(f)).containsExactly(source)
        assertThat(removedIdCount(f)).isZero()
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId).revision).isEqualTo(before.revision)
    }

    @Test
    fun `actual pin cleanup rejection rolls back replacement location and revision`() {
        val f = fixture()
        val before = add(f, "a")
        val source = before.locations.single().id
        pin(f, source, "a")
        jdbc.execute("""CREATE FUNCTION location_intent_reject_pin() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''injected pin deletion failure''; END'""")
        jdbc.execute("""CREATE TRIGGER location_intent_reject_pin BEFORE DELETE ON pinned_places
            FOR EACH ROW EXECUTE FUNCTION location_intent_reject_pin()""")
        assertThatThrownBy {
            apply(f, BundleLocationIntentRequest(BundleLocationIntent.REPLACE,
                targetLocationId = source, location = place("b")))
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId).locations).isEqualTo(before.locations)
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId).revision).isEqualTo(before.revision)
        assertThat(pinSources(f)).containsExactly(source)
        assertThat(removedIdCount(f)).isZero()
    }

    @Test
    fun `HTTP enforces auth payload cap and returns distinguishable atomic location conflicts`() {
        val f = fixture()
        val path = "/api/v1/bunches/${f.bunchId}/bundles/${f.bundleId}/locations/intents"
        val pair = tokens.generateToken(f.actor.memberId, "ROLE_USER", f.actor.sessionId)
        sessions.save(f.actor.sessionId, f.actor.memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        credentials.add(f.actor.memberId to f.actor.sessionId)
        val auth = "Bearer ${pair.accessToken}"
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        listOf("{}", """{"intent":null}""", """{"intent":"UNKNOWN"}""",
            """{"intent":"ADD","locations":null}""").forEach { body ->
            mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
        mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(BundleLocationIntentRequest(
                BundleLocationIntent.ADD, locations = (1..4).map { place("$it") }
            )))).andExpect(status().isBadRequest)
        add(f, "seed", "other")
        mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(BundleLocationIntentRequest(
                BundleLocationIntent.ADD, locations = listOf(place("first"), place("second"))
            )))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("BUNDLE_002"))
            .andExpect(jsonPath("$.error.detail.reason").value("LOCATION_CAPACITY_EXCEEDED"))
        mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(BundleLocationIntentRequest(
                BundleLocationIntent.ADD, locations = listOf(place("seed").copy(provider = " KAKAO "))
            )))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.detail.reason").value("LOCATION_DUPLICATE"))
        val deletedId = locations.get(f.actor.memberId, f.bunchId, f.bundleId).locations.first().id
        apply(f, BundleLocationIntentRequest(BundleLocationIntent.REMOVE, targetLocationId = deletedId))
        mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(BundleLocationIntentRequest(
                BundleLocationIntent.MOVE, targetLocationId = deletedId
            )))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.detail.reason").value("LOCATION_REMOVED"))
    }

    @Test
    fun `HTTP location intents preserve null address while rejecting blank replacements atomically`() {
        val f = fixture()
        val path = "/api/v1/bunches/${f.bunchId}/bundles/${f.bundleId}/locations/intents"
        val pair = tokens.generateToken(f.actor.memberId, "ROLE_USER", f.actor.sessionId)
        sessions.save(f.actor.sessionId, f.actor.memberId, pair.refreshToken, tokens.getRefreshTokenValiditySeconds())
        credentials.add(f.actor.memberId to f.actor.sessionId)
        val authorization = "Bearer ${pair.accessToken}"
        val response = mvc.perform(post(path).header("Authorization", authorization)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"intent":"ADD","locations":[{"provider":"kakao","externalId":"region","name":"여의도","address":null}]}"""))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
        val saved = mapper.readTree(response).path("data")
        assertThat(saved.path("locations").single().has("address")).isTrue()
        assertThat(saved.path("locations").single().path("address").isNull).isTrue()
        val before = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        val id = before.locations.single().id
        listOf("", "   ").forEach { address ->
            mvc.perform(post(path).header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(BundleLocationIntentRequest(
                    BundleLocationIntent.REPLACE,
                    targetLocationId = id,
                    location = BundleLocationIntentPlace("kakao", "new", "성수동", address)
                )))).andExpect(status().isBadRequest)
        }
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId)).isEqualTo(before)
    }

    private fun assertReplacementRejected(f: Fixture, targetId: Long, replacement: BundleLocationIntentPlace) {
        assertUnchangedConflict(f, "LOCATION_DUPLICATE") {
            apply(f, BundleLocationIntentRequest(
                BundleLocationIntent.REPLACE, targetLocationId = targetId, location = replacement
            ))
        }
    }

    private fun assertUnchangedConflict(f: Fixture, reason: String, action: () -> Any?) {
        val before = locations.get(f.actor.memberId, f.bunchId, f.bundleId)
        val metadataSql = "SELECT revision, location_version, created_at, updated_at FROM bundles WHERE id = ?"
        val beforeMetadata = jdbc.queryForMap(metadataSql, f.bundleId)
        val pinsSql = "SELECT * FROM pinned_places WHERE source_bundle_id = ? ORDER BY id"
        val beforePins = jdbc.queryForList(pinsSql, f.bundleId)
        val activitySql = "SELECT * FROM bunch_memberships WHERE bunch_id = ? ORDER BY id"
        val beforeActivity = jdbc.queryForList(activitySql, f.bunchId)
        val beforeRemovedIds = removedIdCount(f)

        val failure = runCatching(action).exceptionOrNull()

        assertConflict(failure, reason)
        assertThat(locations.get(f.actor.memberId, f.bunchId, f.bundleId)).isEqualTo(before)
        assertThat(jdbc.queryForMap(metadataSql, f.bundleId)).isEqualTo(beforeMetadata)
        assertThat(jdbc.queryForList(pinsSql, f.bundleId)).isEqualTo(beforePins)
        assertThat(jdbc.queryForList(activitySql, f.bunchId)).isEqualTo(beforeActivity)
        assertThat(removedIdCount(f)).isEqualTo(beforeRemovedIds)
    }

    private fun assertConflict(failure: Throwable?, reason: String) {
        assertThat(failure).isInstanceOf(BusinessException::class.java)
        val business = failure as BusinessException
        assertThat(business.errorCode).isEqualTo(ErrorCode.BUNDLE_STATE_CONFLICT)
        assertThat((business.detail as? Map<*, *>)?.get("reason")).isEqualTo(reason)
    }

    private fun fixture(): Fixture {
        val actor = BundleActor(member(), UUID.randomUUID().toString())
        val guest = BundleActor(member(), UUID.randomUUID().toString())
        val bunchId = create(actor.memberId).id
        join(guest.memberId, issue(actor.memberId, bunchId))
        return newDraft(actor, guest, bunchId)
    }

    private fun newDraft(actor: BundleActor, guest: BundleActor, bunchId: Long): Fixture {
        val bundle = bundles.saveAndFlush(Bundle(bunchId = bunchId, createdBy = actor.memberId))
        listOf(actor, guest).forEach {
            participants.saveAndFlush(BundleParticipant(bundleId = bundle.id, memberId = it.memberId))
        }
        return Fixture(actor, guest, bunchId, bundle.id)
    }

    private fun place(id: String) = BundleLocationIntentPlace("kakao", id, "place $id", "address $id")

    private fun add(f: Fixture, vararg ids: String) = apply(f, BundleLocationIntentRequest(
        BundleLocationIntent.ADD, locations = ids.map(::place)
    ))

    private fun apply(f: Fixture, request: BundleLocationIntentRequest): BundleLocationIntentResponse =
        locations.applyIntent(f.actor, f.bunchId, f.bundleId, request, UUID.randomUUID().toString())

    private fun pin(f: Fixture, source: Long, externalId: String) {
        // Drafts cannot be pinned through the public writer. Seed a real FK-backed source to
        // verify the existing synchronous cleanup used by both draft and published editors.
        jdbc.update("""INSERT INTO pinned_places(bunch_id, provider, external_id, name, address,
            source_type, source_bundle_id, source_location_id, pinned_by_member_id, pinned_at)
            VALUES (?, 'kakao', ?, 'original', 'address', 'BUNDLE', ?, ?, ?, CURRENT_TIMESTAMP)""",
            f.bunchId, externalId, f.bundleId, source, f.actor.memberId)
    }

    private fun pinSources(f: Fixture): List<Long> = jdbc.queryForList(
        "SELECT source_location_id FROM pinned_places WHERE source_bundle_id = ? ORDER BY source_location_id",
        Long::class.java, f.bundleId
    )

    private fun removedIdCount(f: Fixture): Long = jdbc.queryForObject(
        "SELECT count(*) FROM bundle_requests WHERE bundle_id = ? AND operation = 'LOCATION_REMOVED'",
        Long::class.java, f.bundleId
    )!!

    private data class Fixture(val actor: BundleActor, val guest: BundleActor, val bunchId: Long, val bundleId: Long)

    @TestConfiguration
    class EventConfiguration {
        @Bean
        fun removalRejection() = RemovalRejection()
    }

    class RemovalRejection {
        var reject = false

        @EventListener
        fun removed(event: BundleLocationsRemovedEvent) {
            if (reject) error("injected removal rejection for ${event.bundleId}")
        }
    }
}
