package com.bunch.bunch.service

import com.bunch.bunch.domain.Invitation
import com.bunch.bunch.domain.InvitationRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Locale

@Component
class InvitationInput(
    private val invitations: InvitationRepository,
    @Value("\${bunch.invitation-base-url:http://localhost:3000/invite}") baseUrl: String
) {
    private val base = URI(baseUrl.trimEnd('/'))

    init {
        require(base.scheme in setOf("http", "https") && base.host != null && base.userInfo == null && base.rawQuery == null && base.rawFragment == null)
    }

    fun url(token: String): String = "${base.toASCIIString()}/$token"

    // Expired and revoked rows deliberately remain resolvable for successful join replays.
    fun resolve(input: String): Invitation? {
        val value = input.trim()
        if (value.length > 2048) return null
        if (TOKEN.matches(value)) return invitations.findByToken(value)
        val code = value.uppercase(Locale.ROOT)
        if (CODE.matches(code)) return invitations.findByCode(code)
        val uri = try { URI(value) } catch (_: Exception) { return null }
        if (!uri.scheme.equals(base.scheme, ignoreCase = true) || !uri.host.equals(base.host, ignoreCase = true) ||
            port(uri) != port(base) || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val prefix = "${base.rawPath}/"
        if (!uri.rawPath.orEmpty().startsWith(prefix)) return null
        val token = uri.rawPath.removePrefix(prefix)
        return if (TOKEN.matches(token)) invitations.findByToken(token) else null
    }

    private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val CODE = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}")
    }
}
