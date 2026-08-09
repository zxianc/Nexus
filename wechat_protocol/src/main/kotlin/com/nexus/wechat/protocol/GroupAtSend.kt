package com.nexus.wechat.protocol

/**
 * Build WeChat group @ send payload.
 *
 * Non-PPC path (default): `com.tencent.mm.ui.i1` maps `@display\u2005` → wxid via
 * static LinkedHashMap, then `kl5.s5.zj` puts `atuserlist` CDATA into send Map `h`.
 * PPC path: `zj` p24 = atuserlist CSV → `y11.r1.n`.
 */
object GroupAtSend {
    /** WeChat separator after each @mention in chat content (U+2005). */
    const val AT_SEP = '\u2005'

    data class Mention(
        val wxid: String,
        val display: String,
    )

    data class Built(
        val content: String,
        val atuserlist: String,
        val hasAts: Boolean,
        val mentions: List<Mention> = emptyList(),
    ) {
        val atuserlistCdata: String
            get() = if (atuserlist.isEmpty()) "" else atuserlistCdata(atuserlist)
    }

    fun normalizeAts(ats: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (raw in ats) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            val key = if (id.equals(GroupAtParser.NOTIFY_ALL, ignoreCase = true)) {
                GroupAtParser.NOTIFY_ALL
            } else {
                id
            }
            if (seen.add(key)) out.add(key)
        }
        return out
    }

    fun atuserlistCsv(ats: List<String>): String = normalizeAts(ats).joinToString(",")

    fun atuserlistCdata(csv: String): String = "<![CDATA[$csv]]>"

    fun displayForAt(wxid: String, resolve: (String) -> String): String {
        if (wxid.equals(GroupAtParser.NOTIFY_ALL, ignoreCase = true)) return "所有人"
        val name = resolve(wxid).trim()
        return name.ifEmpty { wxid }
    }

    fun formatContent(text: String, displays: List<String>): String {
        if (displays.isEmpty()) return text
        val missing = displays.filter { display ->
            val token = "@$display$AT_SEP"
            !text.contains(token) && !text.contains("@$display ")
        }
        if (missing.isEmpty()) return text
        val prefix = missing.joinToString("") { "@$it$AT_SEP" }
        return prefix + text
    }

    fun build(
        text: String,
        ats: List<String>,
        resolveDisplay: (String) -> String,
    ): Built {
        val normalized = normalizeAts(ats)
        if (normalized.isEmpty()) {
            return Built(content = text, atuserlist = "", hasAts = false)
        }
        val mentions = normalized.map { wxid ->
            Mention(wxid = wxid, display = displayForAt(wxid, resolveDisplay))
        }
        return Built(
            content = formatContent(text, mentions.map { it.display }),
            atuserlist = atuserlistCsv(normalized),
            hasAts = true,
            mentions = mentions,
        )
    }
}
