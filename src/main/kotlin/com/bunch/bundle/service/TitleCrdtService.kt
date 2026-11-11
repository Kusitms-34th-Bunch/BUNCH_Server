package com.bunch.bundle.service

import com.bunch.bundle.domain.BundleText
import com.bunch.bundle.dto.TitleDocument
import com.bunch.bundle.dto.TitleMergeResult
import com.bunch.global.exception.BusinessException
import com.bunch.global.exception.ErrorCode
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.springframework.stereotype.Service

/** Only the title text uses Automerge; authorization and persistence stay in BundleService. */
@Service
class TitleCrdtService {
    fun initialState(title: String): ByteArray = withDocument(Document()) { document ->
        document.startTransaction().use { transaction ->
            val text = transaction.set(ObjectId.ROOT, "title", ObjectType.TEXT)
            transaction.spliceText(text, 0, 0, title)
            transaction.commit()
        }
        document.save()
    }

    fun read(state: ByteArray): TitleDocument = load(state) { document ->
        val text = titleObject(document)
        val value = document.text(text).orElseThrow { invalid() }
        validateText(value)
        TitleDocument(BundleText.mergedTitle(value), BundleText.titleTooLong(value))
    }

    fun merge(currentState: ByteArray?, incomingState: ByteArray): TitleMergeResult =
        load(incomingState) { incoming ->
            val incomingTitle = titleObject(incoming)
            val currentBytes = currentState ?: invalid()
            load(currentBytes) { current ->
                val currentTitle = titleObject(current)
                // Replacing the root text would turn a title into an LWW map register.
                if (incomingTitle != currentTitle) invalid()
                current.merge(incoming)
                result(current)
            }
        }

    fun rebase(currentState: ByteArray?, value: String): TitleMergeResult {
        if (currentState == null) return TitleMergeResult(initialState(value), value, BundleText.titleTooLong(value))
        return load(currentState) { document ->
            val text = titleObject(document)
            val old = document.text(text).orElseThrow { invalid() }
            if (old != value) {
                document.startTransaction().use { transaction ->
                    transaction.spliceText(text, 0, document.length(text), value)
                    transaction.commit()
                }
            }
            result(document)
        }
    }

    private fun result(document: Document): TitleMergeResult {
        val text = titleObject(document)
        val value = document.text(text).orElseThrow { invalid() }
        validateText(value)
        val bytes = document.save()
        if (bytes.size > MAX_STATE_BYTES) invalid()
        return TitleMergeResult(bytes, BundleText.mergedTitle(value), BundleText.titleTooLong(value))
    }

    private fun titleObject(document: Document): ObjectId {
        if (document.keys(ObjectId.ROOT).orElse(emptyArray()).toList() != listOf("title")) invalid()
        val values = document.getAll(ObjectId.ROOT, "title").orElseThrow { invalid() }.values()
        if (values.size != 1) invalid()
        val text = values.single() as? AmValue.Text ?: invalid()
        if (document.marks(text.id).isNotEmpty()) invalid()
        for (index in 0 until document.length(text.id)) {
            val items = document.getAll(text.id, index).orElseThrow { invalid() }.values()
            if (items.isEmpty() || items.any { it !is AmValue.Str }) invalid()
        }
        return text.id
    }

    private fun validateText(value: String) {
        // Preserve CRDT positions: normalize only the displayed value, never rewrite remote operations.
        if (value.length > MAX_STATE_BYTES || value.any { it == '\u0000' || it in "\r\n\u0085\u2028\u2029" }) invalid()
    }

    private fun <T> load(bytes: ByteArray, action: (Document) -> T): T {
        // Full save format only. Incremental changes/pending dependency queues are not this protocol.
        if (bytes.size !in 9..MAX_STATE_BYTES || !bytes.take(4).toByteArray().contentEquals(MAGIC) || bytes[8] != 0.toByte()) {
            invalid()
        }
        var offset = 9
        var length = 0L
        var shift = 0
        while (true) {
            if (offset >= bytes.size || shift > 28) invalid()
            val byte = bytes[offset++].toInt() and 255
            length = length or ((byte and 127).toLong() shl shift)
            if (byte and 128 == 0) break
            shift += 7
        }
        if (offset.toLong() + length != bytes.size.toLong()) invalid()
        return try {
            withDocument(Document.load(bytes), action)
        } catch (error: BusinessException) {
            throw error
        } catch (_: RuntimeException) {
            invalid()
        }
    }

    private fun <T> withDocument(document: Document, action: (Document) -> T): T = try {
        action(document)
    } finally {
        document.free()
    }

    private fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT)

    private companion object {
        const val MAX_STATE_BYTES = 256 * 1024
        val MAGIC = byteArrayOf(0x85.toByte(), 0x6f, 0x4a, 0x83.toByte())
    }
}
