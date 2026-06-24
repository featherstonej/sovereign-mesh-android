package com.sovereignmesh.android.hardware.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbSerialManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        const val ACTION_USB_PERMISSION = "com.sovereignmesh.android.USB_PERMISSION"
        
        // Chipset VIDs
        private const val VID_SILABS = 0x10C4  // CP210x
        private const val VID_WCH = 0x1A86     // CH340 / CH341
        private const val VID_FTDI = 0x0403    // FTDI
    }

    /**
     * Scans and returns all connected USB devices that appear to be USB-to-UART or CDC-ACM peripherals.
     */
    fun findConnectedDevices(): List<UsbDevice> {
        val deviceList = usbManager.deviceList
        val compatibleDevices = mutableListOf<UsbDevice>()

        for (device in deviceList.values) {
            val vid = device.vendorId
            val deviceClass = device.deviceClass

            Log.d("UsbSerialManager", "Found device: VID=0x${Integer.toHexString(vid)}, PID=0x${Integer.toHexString(device.productId)}, Class=$deviceClass")

            if (isSupportedDevice(device)) {
                compatibleDevices.add(device)
            }
        }
        return compatibleDevices
    }

    /**
     * Checks if the device matches a known serial bridge VID or presents communication/data interfaces.
     */
    private fun isSupportedDevice(device: UsbDevice): Boolean {
        val vid = device.vendorId
        if (vid == VID_SILABS || vid == VID_WCH || vid == VID_FTDI) {
            return true
        }

        // Check if device declares itself as communication device class
        if (device.deviceClass == UsbConstants.USB_CLASS_COMM) {
            return true
        }

        // Check individual interfaces for CDC ACM Data/Comm interfaces
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM || 
                intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether the app already has permission to communicate with the USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Requests runtime USB connection permission from the Android OS.
     */
    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            // Target our application package to avoid generic intent intercepts
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Resolves the appropriate UsbSerialDriver instance based on Vendor ID and features.
     */
    fun getDriverForDevice(device: UsbDevice): UsbSerialDriver? {
        if (!hasPermission(device)) {
            Log.e("UsbSerialManager", "No permission to create driver for device: ${device.deviceName}")
            return null
        }

        return when (device.vendorId) {
            VID_SILABS -> {
                Log.d("UsbSerialManager", "Instantiating CP210x Driver")
                Cp210xDriver(usbManager, device)
            }
            VID_WCH -> {
                Log.d("UsbSerialManager", "Instantiating CH34x Driver")
                Ch34xDriver(usbManager, device)
            }
            else -> {
                Log.d("UsbSerialManager", "Fallback: Instantiating CDC-ACM Driver")
                CdcAcmDriver(usbManager, device)
            }
        }
    }
}
