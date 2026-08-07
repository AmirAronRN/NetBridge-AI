package com.netbridge.ai.state

import kotlinx.coroutines.flow.MutableStateFlow

object NetBridgeState {
    val isRunning = MutableStateFlow(false)
    val proxyPort = MutableStateFlow(8080)
    val activeInterfaces = MutableStateFlow<List<String>>(emptyList())
    
    val connectedClients = MutableStateFlow(0)
    val uploadSpeedKbps = MutableStateFlow(0L)
    val downloadSpeedKbps = MutableStateFlow(0L)
}
