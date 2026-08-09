package com.nexus.wechat.protocol

/**
 * Decide whether a chat_id is safe to send to (avoid creating stranger sessions).
 *
 * Pure policy — callers supply contact/chatroom facts from WeChat DB.
 */
object ChatSendPolicy {
    data class ContactRow(
        val type: Int,
        val deleteFlag: Int = 0,
    )

    /**
     * @return null if allowed, otherwise an error code for SEND_RESULT / HTTP.
     */
    fun validate(
        chatId: String,
        contact: ContactRow?,
        chatroomKnown: Boolean,
    ): String? {
        val id = chatId.trim()
        if (id.isEmpty()) return "missing_fields"
        if (id == "filehelper") return null
        if (id.startsWith("fake_") || id.endsWith("@stranger")) return "unknown_chat"

        if (id.endsWith("@chatroom")) {
            if (chatroomKnown) return null
            // Some rooms only appear in rcontact.
            if (contact != null && contact.deleteFlag == 0) return null
            return "unknown_chat"
        }

        if (contact == null) return "unknown_chat"
        if (contact.deleteFlag != 0) return "not_friend"
        // WeChat rcontact: bit0 set ≈ in address book / friend (also used by our chat list SQL).
        if (contact.type and 1 == 0) return "not_friend"
        return null
    }
}
