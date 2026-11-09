package com.bunch.notification.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class DeviceTokenCipher(
    @Value("\${notification.push.device-encryption-key:}")
    private val configuredKey: String
) {
    private val random = SecureRandom()

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }

    fun encrypt(token: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return ByteBuffer.allocate(iv.size + cipher.getOutputSize(token.toByteArray().size))
            .put(iv)
            .put(cipher.doFinal(token.toByteArray(Charsets.US_ASCII)) )
            .array()
    }

    fun decrypt(ciphertext: ByteArray): String {
        require(ciphertext.size > IV_BYTES) { "Invalid device token ciphertext" }
        val buffer = ByteBuffer.wrap(ciphertext)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.US_ASCII)
    }

    private fun key(): SecretKeySpec {
        val decoded = Base64.getDecoder().decode(configuredKey)
        require(decoded.size == KEY_BYTES) { "Device token encryption key must be 256 bits" }
        return SecretKeySpec(decoded, "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
