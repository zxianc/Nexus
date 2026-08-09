package com.nexus.wechat.protocol

/**
 * Parse WeChat group @ targets from msgSource / lvbuffer.
 *
 * Common form: `<msgsource><atuserlist>wxid_a,wxid_b</atuserlist></msgsource>`
 * Everyone: `notify@all`
 */
object GroupAtParser {
    const val NOTIFY_ALL = "notify@all"

    data class Result(
        val ats: List<String>,
        val atAll: Boolean,
        val atMe: Boolean,
    )

    fun parse(
        msgSource: String?,
        lvbuffer: ByteArray?,
        selfId: String,
    ): Result {
        val raw = extractAtUserList(msgSource)
            ?: extractAtUserList(decodeLoose(lvbuffer))
            ?: return Result(ats = emptyList(), atAll = false, atMe = false)
        val ats = raw.split(',', '，', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val atAll = ats.any { it.equals(NOTIFY_ALL, ignoreCase = true) }
        val self = selfId.trim()
        val atMe = atAll || (self.isNotEmpty() && ats.any { it == self })
        return Result(ats = ats, atAll = atAll, atMe = atMe)
    }

    private fun extractAtUserList(text: String?): String? {
        if (text.isNullOrBlank()) return null
        // CDATA or plain
        val cdata = Regex(
            """(?is)<atuserlist>\s*<!\[CDATA\[(.*?)]]>\s*</atuserlist>""",
        ).find(text)?.groupValues?.getOrNull(1)
        if (!cdata.isNullOrBlank()) return cdata.trim()
        val plain = Regex(
            """(?is)<atuserlist>(.*?)</atuserlist>""",
        ).find(text)?.groupValues?.getOrNull(1)
        return plain?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeLoose(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        // Prefer UTF-8; fall back to latin1 so binary blobs still expose ASCII tags.
        val utf8 = try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
        if (utf8 != null && utf8.contains("atuserlist", ignoreCase = true)) return utf8
        val latin = bytes.toString(Charsets.ISO_8859_1)
        return latin.takeIf { it.contains("atuserlist", ignoreCase = true) }
    }
}
