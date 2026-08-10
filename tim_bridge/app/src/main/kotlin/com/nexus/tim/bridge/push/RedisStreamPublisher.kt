package com.nexus.tim.bridge.push

import android.util.Log
import com.nexus.tim.bridge.config.BridgeConfig
import org.json.JSONObject
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.params.XAddParams
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Async XADD into a Redis Stream. Pool JMX disabled for Android. */
class RedisStreamPublisher(
    private val configProvider: () -> BridgeConfig,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tim-redis-xadd").apply { isDaemon = true }
    }
    private val pool = AtomicReference<JedisPooled?>(null)
    private val poolKey = AtomicReference("")

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastOkAtMs: Long = 0L
        private set

    fun publish(type: String, payload: JSONObject, onFailure: ((String) -> Unit)? = null) {
        executor.execute {
            val cfg = configProvider().normalized()
            if (!cfg.redisReady) return@execute
            try {
                val jedis = ensurePool(cfg)
                val fields = RedisEventPayload.streamFields(type, payload)
                jedis.xadd(cfg.redisStreamKey, XAddParams.xAddParams(), fields)
                lastOkAtMs = System.currentTimeMillis()
                lastError = null
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}:${t.message}"
                lastError = msg
                Log.w(TAG, "redis xadd failed: $msg")
                closePool()
                onFailure?.invoke(msg)
            }
        }
    }

    fun close() {
        executor.shutdownNow()
        closePool()
    }

    private fun ensurePool(cfg: BridgeConfig): JedisPooled {
        val key = "${cfg.redisHost}:${cfg.redisPort}:${cfg.redisPassword.hashCode()}"
        pool.get()?.let { existing ->
            if (poolKey.get() == key) return existing
        }
        closePool()
        val builder = DefaultJedisClientConfig.builder().timeoutMillis(3_000)
        if (cfg.redisPassword.isNotEmpty()) {
            builder.password(cfg.redisPassword)
        }
        val poolConfig = ConnectionPoolConfig().apply {
            jmxEnabled = false
            maxTotal = 4
            maxIdle = 2
            minIdle = 0
            testOnBorrow = false
        }
        val created = JedisPooled(
            HostAndPort(cfg.redisHost, cfg.redisPort),
            builder.build(),
            poolConfig,
        )
        pool.set(created)
        poolKey.set(key)
        return created
    }

    private fun closePool() {
        try {
            pool.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
        poolKey.set("")
    }

    companion object {
        private const val TAG = "TimBridgeRedis"
    }
}
