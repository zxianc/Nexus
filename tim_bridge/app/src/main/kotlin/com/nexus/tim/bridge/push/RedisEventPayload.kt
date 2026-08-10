package com.nexus.tim.bridge.push

import org.json.JSONObject

object RedisEventPayload {
    fun streamFields(
        type: String,
        payload: JSONObject,
        tsMs: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        return mapOf(
            "type" to type,
            "ts" to tsMs.toString(),
            "data" to payload.toString(),
        )
    }
}
