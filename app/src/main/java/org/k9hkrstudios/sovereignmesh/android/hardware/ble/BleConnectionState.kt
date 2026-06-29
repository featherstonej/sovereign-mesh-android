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

package org.k9hkrstudios.sovereignmesh.android.hardware.ble

/**
 * Represents the current lifecycle state of a Bluetooth Low Energy connection.
 */
enum class BleConnectionState {
    /** BLE client is idle and disconnected. */
    DISCONNECTED,
    /** Actively scanning for nearby Meshtastic peripherals. */
    SCANNING,
    /** Attempting to establish a GATT connection and discover services. */
    CONNECTING,
    /** GATT connection established and notifications enabled. */
    CONNECTED,
    /** An unrecoverable GATT or configuration error occurred. */
    ERROR
}
