package com.bunch.bundle

import com.bunch.bundle.service.TitleCrdtService
import com.bunch.global.exception.BusinessException
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TitleCrdtServiceTest {
    private val service = TitleCrdtService()

    @Test
    fun `official JS saved title document is readable by the JVM binding`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/bundle/title-js-3.2.1.automerge"))
            .use { it.readBytes() }
        assertThat(service.read(bytes).value).isEqualTo("부산 여행 1일차")
    }

    @Test
    fun `Korean concurrent prefix edit and suffix insertion converge without replacing title`() {
        val base = service.initialState("제주 여행")
        val first = edit(base, 0, 2, "부산")
        val second = edit(base, 5, 0, " 1일차")
        val forward = service.merge(service.merge(base, first).state, second)
        val reverse = service.merge(service.merge(base, second).state, first)
        assertThat(forward.value).isEqualTo("부산 여행 1일차")
        assertThat(reverse.value).isEqualTo(forward.value)
        assertThat(service.merge(forward.state, first).state).containsExactly(*forward.state)
        assertThat(service.read(forward.state).value).isEqualTo(forward.value)
    }

    @Test
    fun `merged overflow remains intact and can be shortened with an emoji legacy rebase`() {
        val base = service.initialState("가".repeat(39))
        val first = edit(base, 0, 0, "A")
        val second = edit(base, 39, 0, "B")
        val merged = service.merge(service.merge(base, first).state, second)
        assertThat(merged.value).hasSize(41)
        assertThat(merged.tooLong).isTrue()
        val shorter = service.rebase(merged.state, "여행 🏖️")
        assertThat(shorter.tooLong).isFalse()
        assertThat(service.read(shorter.state).value).isEqualTo("여행 🏖️")
        assertThat(service.rebase(shorter.state, "다음 여행").value).isEqualTo("다음 여행")
    }

    @Test
    fun `unrelated documents root properties newline malformed and oversized input are rejected`() {
        val base = service.initialState("제목")
        assertInvalid { service.merge(base, service.initialState("다른 문서")) }
        assertInvalid { service.merge(base, byteArrayOf(1, 2, 3)) }
        assertInvalid { service.merge(base, base + byteArrayOf(1)) }
        assertInvalid { service.merge(base, base.copyOf(base.size - 1)) }
        assertInvalid { service.merge(base, ByteArray(256 * 1024 + 1)) }
        assertInvalid { service.merge(base, edit(base, 2, 0, "\n줄")) }
        val document = Document.load(base)
        try {
            document.startTransaction().use { transaction ->
                transaction.set(ObjectId.ROOT, "visibility", "PUBLIC")
                transaction.commit()
            }
            assertInvalid { service.merge(base, document.save()) }
        } finally {
            document.free()
        }
    }

    @Test
    fun `text sequence rejects embedded maps and null values`() {
        val base = service.initialState("제목")
        listOf(true, false).forEach { map ->
            val document = Document.load(base)
            try {
                val text = (document.get(ObjectId.ROOT, "title").get() as AmValue.Text).id
                document.startTransaction().use {
                    if (map) it.insert(text, 1, org.automerge.ObjectType.MAP) else it.insertNull(text, 1)
                    it.commit()
                }
                assertInvalid { service.merge(base, document.save()) }
                assertThat(service.read(base).value).isEqualTo("제목")
            } finally {
                document.free()
            }
        }
    }

    private fun edit(base: ByteArray, index: Long, removed: Long, text: String): ByteArray {
        val document = Document.load(base)
        try {
            val title = (document.get(ObjectId.ROOT, "title").get() as AmValue.Text).id
            document.startTransaction().use { transaction ->
                transaction.spliceText(title, index, removed, text)
                transaction.commit()
            }
            return document.save()
        } finally {
            document.free()
        }
    }

    private fun assertInvalid(action: () -> Any?) {
        assertThatThrownBy { action() }.isInstanceOf(BusinessException::class.java)
    }
}
