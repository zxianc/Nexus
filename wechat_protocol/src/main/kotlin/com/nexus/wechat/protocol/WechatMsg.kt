package com.nexus.wechat.protocol

/**
 * JSON field names for UDS payloads (org.json / Gson compatible).
 * Parsing helpers can be added when Bridge wires them; frame Task only needs binary codec.
 */
object WechatMsgFields {
    const val REQUEST_ID = "request_id"
    const val CHAT_ID = "chat_id"
    const val TEXT = "text"
    const val ATS = "ats"
    const val OK = "ok"
    const val MSG_ID = "msg_id"
    const val ERROR = "error"
    const val FROM_ID = "from_id"
    const val IS_GROUP = "is_group"
    const val MEDIA_ID = "media_id"
    const val MEDIA_KIND = "media_kind"
    const val MEDIA_NAME = "media_name"
    const val TS = "ts"
    const val PATH = "path"
    const val NAME = "name"
    const val KIND = "kind"
    const val WECHAT_VERSION = "wechat_version"
    const val LOGGED_IN = "logged_in"
    const val USER_ID = "user_id"
    const val NICK = "nick"
    const val CHATS = "chats"
}
