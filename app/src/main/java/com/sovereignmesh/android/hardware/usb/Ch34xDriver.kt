package com.sovereignmesh.android.hardware.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class Ch34xDriver(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : UsbSerialDriver {

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>()
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private var ioJob: Job? = null
    private val driverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun connect(): Boolean {
        if (_connectionState.value == UsbConnectionState.CONNECTED) return true
        _connectionState.value = UsbConnectionState.CONNECTING

        try {
            val conn = usbManager.openDevice(device) ?: run {
                Log.e("Ch34xDriver", "Failed to open device connection")
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }
            connection = conn

            if (device.interfaceCount == 0) {
                Log.e("Ch34xDriver", "Device has no interfaces")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            val intf = device.getInterface(0)
            usbInterface = intf

            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        endpointIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        endpointOut = ep
                    }
                }
            }

            if (endpointIn == null || endpointOut == null) {
                Log.e("Ch34xDriver", "Required bulk endpoints not found")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            if (!conn.claimInterface(intf, true)) {
                Log.e("Ch34xDriver", "Failed to claim interface")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            // CH340 UART initialization control transfers
            val reqType = 0x40 // Host to Device, Vendor

            // 1. Initial configuration
            conn.controlTransfer(reqType, 0x5F, 0, 0, null, 0, 5000)
            conn.controlTransfer(reqType, 0xA1, 0, 0, null, 0, 5000)

            // 2. Configure line parameters and baud rate (115200)
            // Register settings for CH340 115200:
            // Divisors: Value 0x1312, Index 0xCC09
            // Value 0x2518, Index 0x00D3
            conn.controlTransfer(reqType, 0x9A, 0x1312, 0xCC09, null, 0, 5000)
            conn.controlTransfer(reqType, 0x9A, 0x2518, 0x00D3, null, 0, 5000)

            // 3. Set control lines / handshake (assert RTS and DTR)
            // Value 0xFF7F represents the control bits for DTR/RTS
            conn.controlTransfer(reqType, 0xA4, 0xFF7F, 0, null, 0, 5000)

            _connectionState.value = UsbConnectionState.CONNECTED
            startIoLoop()
            return true
        } catch (e: Exception) {
            Log.e("Ch34xDriver", "Connection error", e)
            cleanup()
            _connectionState.value = UsbConnectionState.ERROR
            return false
        }
    }

    private fun startIoLoop() {
        ioJob = driverScope.launch {
            val buffer = ByteArray(1024)
            val conn = connection ?: return@launch
            val epIn = endpointIn ?: return@launch

            while (isActive && _connectionState.value == UsbConnectionState.CONNECTED) {
                val bytesRead = conn.bulkTransfer(epIn, buffer, buffer.size, 100)
                if (bytesRead > 0) {
                    val readData = buffer.copyOf(bytesRead)
                    _incomingBytes.emit(readData)
                } else if (bytesRead < 0) {
                    delay(10)
                }
            }
        }
    }

    override fun disconnect() {
        cleanup()
        _connectionState.value = UsbConnectionState.DISCONNECTED
    }

    private fun cleanup() {
        ioJob?.cancel()
        ioJob = null

        connection?.apply {
            try {
                // De-assert control lines on CH340
                controlTransfer(0x40, 0xA4, 0, 0, null, 0, 1000)
                usbInterface?.let { releaseInterface(it) }
                close()
            } catch (e: Exception) {
                Log.w("Ch34xDriver", "Error during release and close", e)
            }
        }
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    override fun write(data: ByteArray): Int {
        val conn = connection ?: return -1
        val epOut = endpointOut ?: return -1
        if (_connectionState.value != UsbConnectionState.CONNECTED) return -1
        
        return conn.bulkTransfer(epOut, data, data.size, 1000)
    }
}
