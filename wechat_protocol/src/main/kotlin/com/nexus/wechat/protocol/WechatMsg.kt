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
    /** True if this message @'d the logged-in user or @all. */
    const val AT_ME = "at_me"
    /** True if atuserlist contains notify@all. */
    const val AT_ALL = "at_all"
    const val OK = "ok"
    const val MSG_ID = "msg_id"
    const val ERROR = "error"
    const val FROM_ID = "from_id"
    const val FROM_DISPLAY = "from_display"
    const val IS_SELF = "is_self"
    const val IS_GROUP = "is_group"
    const val TITLE = "title"
    const val DISPLAY = "display"
    const val MEMBERS = "members"
    const val CHAT_TITLE = "chat_title"
    const val MEDIA_ID = "media_id"
    const val MEDIA_KIND = "media_kind"
    const val MEDIA_NAME = "media_name"
    const val TS = "ts"
    const val PATH = "path"
    const val NAME = "name"
    const val KIND = "kind"
    const val DATA_B64 = "data_b64"
    /** Send image as WeChat "原图" when true (default). */
    const val ORIGINAL = "original"
    const val WECHAT_VERSION = "wechat_version"
    const val LOGGED_IN = "logged_in"
    const val USER_ID = "user_id"
    const val NICK = "nick"
    const val CHATS = "chats"
}
