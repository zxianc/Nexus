package com.nexus.wechat.bridge.store

import org.json.JSONObject

data class BridgeEvent(
    val cursor: Long,
    val type: String,
    val payload: JSONObject,
)

class EventStore {
    private val lock = Any()
    private val events = ArrayList<BridgeEvent>()
    private var nextCursor = 1L

    fun append(event: BridgeEvent): Long = synchronized(lock) {
        val cursor = nextCursor++
        val stored = event.copy(cursor = cursor)
        events.add(stored)
        cursor
    }

    fun after(cursor: Long): List<BridgeEvent> = synchronized(lock) {
        events.filter { it.cursor > cursor }
    }

    fun latestCursor(): Long = synchronized(lock) {
        events.lastOrNull()?.cursor ?: 0L
    }
}
