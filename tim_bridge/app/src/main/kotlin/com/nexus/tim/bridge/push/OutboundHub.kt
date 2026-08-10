package com.nexus.tim.bridge.push

import com.nexus.tim.bridge.config.BridgeConfig
import org.json.JSONObject

/** Fan-out: Redis for events, Webhook for alerts. */
class OutboundHub(
    private val configProvider: () -> BridgeConfig,
) {
    private val redis = RedisStreamPublisher(configProvider)
    private val webhook = WebhookAlerter(configProvider)

    val redisLastError: String? get() = redis.lastError
    val redisLastOkAtMs: Long get() = redis.lastOkAtMs

    fun publishEvent(type: String, payload: JSONObject) {
        redis.publish(type, payload) { err ->
            webhook.alert(
                code = "redis_publish_failed",
                message = "Redis XADD failed: $err",
                detail = JSONObject().put("type", type).put("error", err),
            )
        }
    }

    fun alert(code: String, message: String, detail: JSONObject? = null) {
        webhook.alert(code, message, detail)
    }

    fun close() {
        redis.close()
        webhook.close()
    }
}
