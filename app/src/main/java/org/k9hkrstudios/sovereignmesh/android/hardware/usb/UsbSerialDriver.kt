/*
 * Sovereign Mesh (Android)
 * Copyright (C) 2025 Sovereign Mesh Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.k9hkrstudios.sovereignmesh.android.hardware.usb

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents the current state of a USB-OTG connection.
 */
enum class UsbConnectionState {
    /** Interface is closed and no device is connected. */
    DISCONNECTED,
    /** Actively attempting to open the USB interface. */
    CONNECTING,
    /** Interface is open and ready for bi-directional communication. */
    CONNECTED,
    /** An unrecoverable error occurred during connection or communication. */
    ERROR
}

/**
 * Common interface for chipset-specific USB serial drivers.
 */
interface UsbSerialDriver {
    /** Observed state of the USB connection. */
    val connectionState: StateFlow<UsbConnectionState>
    
    /** Stream of raw bytes received from the hardware. */
    val incomingBytes: SharedFlow<ByteArray>
    
    /**
     * Opens the USB connection and starts the I/O polling loop.
     * @return true if the connection was successfully established.
     */
    fun connect(): Boolean
    
    /**
     * Closes the USB connection and releases all hardware resources.
     */
    fun disconnect()
    
    /**
     * Writes raw bytes to the USB bulk output endpoint.
     * @param data The byte array to transmit.
     * @return The number of bytes written, or -1 on failure.
     */
    fun write(data: ByteArray): Int
}
