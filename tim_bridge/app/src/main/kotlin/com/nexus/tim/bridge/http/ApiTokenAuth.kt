package com.nexus.tim.bridge.http

/**
 * LAN API token check.
 *
 * - `Authorization: Bearer <token>`
 * - `X-Api-Token: <token>`
 * - query `?token=`
 */
object ApiTokenAuth {
    fun isAuthorized(
        enabled: Boolean,
        expectedToken: String,
        headers: Map<String, String>,
        query: Map<String, String>,
    ): Boolean {
        if (!enabled) return true
        val expected = expectedToken.trim()
        if (expected.isEmpty()) return false
        val provided = extractToken(headers, query) ?: return false
        return provided == expected
    }

    fun extractToken(headers: Map<String, String>, query: Map<String, String>): String? {
        val auth = header(headers, "authorization")?.trim().orEmpty()
        if (auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim().takeIf { it.isNotEmpty() }
        }
        header(headers, "x-api-token")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return query["token"]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun header(headers: Map<String, String>, name: String): String? {
        headers[name]?.let { return it }
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let { return it.value }
        return null
    }
}
