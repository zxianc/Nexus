package com.nexus.tim.bridge.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTokenAuthTest {
    @Test
    fun disabled_alwaysAllows() {
        assertTrue(
            ApiTokenAuth.isAuthorized(
                enabled = false,
                expectedToken = "secret",
                headers = emptyMap(),
                query = emptyMap(),
            ),
        )
    }

    @Test
    fun enabled_emptyExpected_denies() {
        assertFalse(
            ApiTokenAuth.isAuthorized(
                enabled = true,
                expectedToken = "",
                headers = mapOf("authorization" to "Bearer x"),
                query = emptyMap(),
            ),
        )
    }

    @Test
    fun bearerHeader_ok() {
        assertTrue(
            ApiTokenAuth.isAuthorized(
                enabled = true,
                expectedToken = "s3cret",
                headers = mapOf("authorization" to "Bearer s3cret"),
                query = emptyMap(),
            ),
        )
    }

    @Test
    fun xApiTokenHeader_ok() {
        assertTrue(
            ApiTokenAuth.isAuthorized(
                enabled = true,
                expectedToken = "s3cret",
                headers = mapOf("x-api-token" to "s3cret"),
                query = emptyMap(),
            ),
        )
    }

    @Test
    fun queryToken_ok() {
        assertTrue(
            ApiTokenAuth.isAuthorized(
                enabled = true,
                expectedToken = "s3cret",
                headers = emptyMap(),
                query = mapOf("token" to "s3cret"),
            ),
        )
    }

    @Test
    fun wrongToken_denied() {
        assertFalse(
            ApiTokenAuth.isAuthorized(
                enabled = true,
                expectedToken = "s3cret",
                headers = mapOf("authorization" to "Bearer no"),
                query = emptyMap(),
            ),
        )
    }
}
