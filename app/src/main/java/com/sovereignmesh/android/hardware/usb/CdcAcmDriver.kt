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

class CdcAcmDriver(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : UsbSerialDriver {

    private val _connectionState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>()
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var controlInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private var ioJob: Job? = null
    private val driverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun connect(): Boolean {
        if (_connectionState.value == UsbConnectionState.CONNECTED) return true
        _connectionState.value = UsbConnectionState.CONNECTING

        try {
            val conn = usbManager.openDevice(device) ?: run {
                Log.e("CdcAcmDriver", "Failed to open device connection")
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }
            connection = conn

            // Find interfaces: CDC ACM usually has control interface (class 2) and data interface (class 10)
            var ctrlIntf: UsbInterface? = null
            var dataIntf: UsbInterface? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                    ctrlIntf = intf
                } else if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                    dataIntf = intf
                }
            }

            // Fallback if interface classes are not strictly declared
            if (dataIntf == null) {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.endpointCount >= 2) {
                        dataIntf = intf
                        break
                    }
                }
            }

            if (dataIntf == null) {
                Log.e("CdcAcmDriver", "No suitable data interface found")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            controlInterface = ctrlIntf
            dataInterface = dataIntf

            // Find endpoints
            for (e in 0 until dataIntf.endpointCount) {
                val ep = dataIntf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        endpointIn = ep
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        endpointOut = ep
                    }
                }
            }

            if (endpointIn == null || endpointOut == null) {
                Log.e("CdcAcmDriver", "Required bulk endpoints not found")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            // Claim interfaces
            if (ctrlIntf != null) {
                if (!conn.claimInterface(ctrlIntf, true)) {
                    Log.w("CdcAcmDriver", "Failed to claim control interface")
                }
            }
            if (!conn.claimInterface(dataIntf, true)) {
                Log.e("CdcAcmDriver", "Failed to claim data interface")
                cleanup()
                _connectionState.value = UsbConnectionState.ERROR
                return false
            }

            // Configure serial line parameters (CDC control request SET_LINE_CODING)
            // 115200 baud, 8 data bits, 1 stop bit, no parity
            val lineCoding = byteArrayOf(
                0x00.toByte(), 0xC2.toByte(), 0x01.toByte(), 0x00.toByte(), // 115200 baud (0x0001C200)
                0x00.toByte(),                   // 1 stop bit
                0x00.toByte(),                   // no parity
                0x08.toByte()                    // 8 data bits
            )
            val ctrlId = ctrlIntf?.id ?: 0
            conn.controlTransfer(0x21, 0x20, 0, ctrlId, lineCoding, lineCoding.size, 5000)

            // SET_CONTROL_LINE_STATE (DTR | RTS)
            conn.controlTransfer(0x21, 0x22, 0x03, ctrlId, null, 0, 5000)

            _connectionState.value = UsbConnectionState.CONNECTED
            startIoLoop()
            return true
        } catch (e: Exception) {
            Log.e("CdcAcmDriver", "Connection error", e)
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
                dataInterface?.let { releaseInterface(it) }
                controlInterface?.let { releaseInterface(it) }
                close()
            } catch (e: Exception) {
                Log.w("CdcAcmDriver", "Error during interface release and close", e)
            }
        }
        connection = null
        dataInterface = null
        controlInterface = null
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
