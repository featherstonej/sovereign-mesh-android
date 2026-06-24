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

class Cp210xDriver(
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

    // CP210x Requests
    private val REQ_IFC_ENABLE = 0x00
    private val REQ_SET_LINE_CTL = 0x03
    private val REQ_SET_MHS = 0x07
    private val REQ_SET_BAUDRATE = 0x1E

    override fun connect(): Boolean {
        if (_connectionState.value == UsbConnectionState.CONNECTED) return true
        _connectionState.value = UsbConnectionState.CONNECTING

        try {
            val conn = usbManager.openDevice(device) ?: run {
                Log.e("Cp210xDriver", "Failed to open device connection")
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }
            connection = conn

            // CP210x typically has a single interface
            if (device.interfaceCount == 0) {
                Log.e("Cp210xDriver", "Device has no interfaces")
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
                Log.e("Cp210xDriver", "Required bulk endpoints not found")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            if (!conn.claimInterface(intf, true)) {
                Log.e("Cp210xDriver", "Failed to claim interface")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            // Vendor Request Type for CP210x: 0x41 (Host to Device, Vendor, Interface)
            val reqType = 0x41

            // 1. Enable UART
            conn.controlTransfer(reqType, REQ_IFC_ENABLE, 1, intf.id, null, 0, 5000)

            // 2. Set Baud Rate (115200)
            val baudData = byteArrayOf(
                0x00.toByte(), 0xC2.toByte(), 0x01.toByte(), 0x00.toByte() // 115200 in little endian hex (0x0001C200)
            )
            conn.controlTransfer(reqType, REQ_SET_BAUDRATE, 0, intf.id, baudData, baudData.size, 5000)

            // 3. Set Line Control (8 data bits, 1 stop bit, no parity) -> value 0x0800
            conn.controlTransfer(reqType, REQ_SET_LINE_CTL, 0x0800, intf.id, null, 0, 5000)

            // 4. Set MHS (assert DTR and RTS) -> value 0x0303
            conn.controlTransfer(reqType, REQ_SET_MHS, 0x0303, intf.id, null, 0, 5000)

            _connectionState.value = UsbConnectionState.CONNECTED
            startIoLoop()
            return true
        } catch (e: Exception) {
            Log.e("Cp210xDriver", "Connection error", e)
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
                // Disable UART on CP210x
                usbInterface?.let {
                    controlTransfer(0x41, REQ_IFC_ENABLE, 0, it.id, null, 0, 1000)
                    releaseInterface(it)
                }
                close()
            } catch (e: Exception) {
                Log.w("Cp210xDriver", "Error during release and close", e)
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
