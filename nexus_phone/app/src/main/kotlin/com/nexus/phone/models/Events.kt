package com.nexus.phone.models

sealed class Events {
    data object RefreshCallLog : Events()
}
