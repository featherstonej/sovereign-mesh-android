package com.sovereignmesh.android.hardware.usb

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class UsbConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

interface UsbSerialDriver {
    val connectionState: StateFlow<UsbConnectionState>
    val incomingBytes: SharedFlow<ByteArray>
    
    fun connect(): Boolean
    fun disconnect()
    fun write(data: ByteArray): Int
}
