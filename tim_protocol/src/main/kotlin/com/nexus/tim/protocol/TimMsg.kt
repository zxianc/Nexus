package com.nexus.tim.protocol

/** JSON field names for TIM UDS payloads. */
object TimMsgFields {
    const val REQUEST_ID = "request_id"
    const val CHAT_ID = "chat_id"
    const val TEXT = "text"
    /** Group @ QQ numbers / notify@all (send + MSG_IN). */
    const val ATS = "ats"
    /** True when this message @'s the logged-in account (or at_all). */
    const val AT_ME = "at_me"
    /** True when message includes @all / notify@all. */
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
    const val TS = "ts"
    const val TIM_VERSION = "tim_version"
    const val LOGGED_IN = "logged_in"
    const val USER_ID = "user_id"
    const val NICK = "nick"
    /** Full friends (HELLO / GET /v1/contacts). */
    const val CONTACTS = "contacts"
    /** Full group list (HELLO / GET /v1/groups). */
    const val GROUPS = "groups"

    /** Outgoing / staged media id (HTTP + SEND_IMAGE). */
    const val MEDIA_ID = "media_id"
    const val MEDIA_KIND = "media_kind"
    const val PATH = "path"
    const val NAME = "name"
    const val KIND = "kind"
    const val DATA_B64 = "data_b64"
    /** Prefer original / less-compressed image when true. */
    const val ORIGINAL = "original"
}
