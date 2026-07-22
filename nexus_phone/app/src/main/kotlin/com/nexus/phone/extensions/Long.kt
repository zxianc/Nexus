package com.nexus.phone.extensions

import org.fossify.commons.extensions.toDayCode

fun Long.getDayCode(): String {
    return toDayCode("yyyy-MM-dd") // format helps with sorting in call log
}
