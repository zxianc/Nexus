package com.nexus.tim.bridge.store

import org.json.JSONObject

data class BridgeEvent(
    val cursor: Long,
    val type: String,
    val payload: JSONObject,
)

class EventStore(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    private val lock = Any()
    private val events = ArrayList<BridgeEvent>()
    private var nextCursor = 1L

    fun append(event: BridgeEvent): Long = synchronized(lock) {
        val cursor = nextCursor++
        events.add(event.copy(cursor = cursor))
        while (events.size > maxEvents) {
            events.removeAt(0)
        }
        cursor
    }

    fun after(cursor: Long): List<BridgeEvent> = synchronized(lock) {
        events.filter { it.cursor > cursor }
    }

    fun latestCursor(): Long = synchronized(lock) {
        events.lastOrNull()?.cursor ?: 0L
    }

    companion object {
        const val DEFAULT_MAX_EVENTS = 200
    }
}
