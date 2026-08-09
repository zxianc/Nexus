package com.nexus.wechat.bridge

import android.app.Application
import com.nexus.wechat.bridge.store.EventStore
import com.nexus.wechat.bridge.state.BridgeState

class BridgeApp : Application() {
    lateinit var eventStore: EventStore
        private set
    lateinit var bridgeState: BridgeState
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        eventStore = EventStore()
        bridgeState = BridgeState(supportedVersion = BridgeState.DEFAULT_SUPPORTED_VERSION)
    }

    companion object {
        lateinit var instance: BridgeApp
            private set
    }
}
