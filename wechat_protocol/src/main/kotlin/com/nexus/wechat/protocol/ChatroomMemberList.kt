package com.nexus.wechat.protocol

/**
 * Parse WeChat `chatroom.memberlist` column.
 *
 * Usual form: `wxid_a;wxid_b;wxid_c` (semicolon). Some paths use commas.
 */
object ChatroomMemberList {
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val sep = when {
            raw.contains(';') -> ';'
            raw.contains(',') -> ','
            else -> ';'
        }
        return raw.split(sep)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
