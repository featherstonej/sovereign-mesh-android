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

package com.sovereignmesh.android.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sovereignmesh.android.database.SignalLog
import com.sovereignmesh.android.ui.theme.CryptoGreen
import com.sovereignmesh.android.ui.theme.CryptoTeal
import com.sovereignmesh.android.ui.theme.StealthSurface
import com.sovereignmesh.android.ui.theme.TextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

/**
 * MapScreen provides a privacy-first tactical mapping interface.
 *
 * It enforces offline operation by blocking data connections in OSMDroid and
 * leverages local signal logs to visualize node health and location.
 */
@Composable
fun MapScreen(
    viewModel: SovereignViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val phoneLocation by viewModel.phoneLocation.collectAsState()
    val usePhoneGps by viewModel.usePhoneGps.collectAsState()
    val peerNodeId = 87654321
    var signalLog by remember { mutableStateOf<SignalLog?>(null) }
    var hasCentered by remember { mutableStateOf(false) }
    
    // Initialize OSMDroid configuration for offline-only tile directory
    remember {
        val sharedPrefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(context, sharedPrefs)
        // User agent required by OSMDroid, set to a generic local name
        Configuration.getInstance().userAgentValue = "SovereignMeshOffline"
    }

    val mapView = remember {
        MapView(context)
    }

    val peerMarker = remember {
        Marker(mapView).apply {
            position = GeoPoint(37.7715, -122.4810)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }

    val myMarker = remember {
        Marker(mapView).apply {
            position = GeoPoint(37.7694, -122.4862)
            title = "Node: Me (0x11223344)"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            val myShape = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(40, 40)
                setColor(android.graphics.Color.BLUE)
                setStroke(2, android.graphics.Color.WHITE)
            }
            icon = myShape
        }
    }

    remember {
        mapView.apply {
            // CRITICAL: Block map from attempting internet downloads to enforce offline privacy
            setUseDataConnection(false)
            
            // Basic UI setups
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(15.0)

            // Central mock coordinates for local mesh coordinate grid (e.g. SF Golden Gate Park area)
            val centerPoint = GeoPoint(37.7694, -122.4862)
            controller.setCenter(centerPoint)

            overlays.add(myMarker)
            overlays.add(peerMarker)
        }
    }

    // Query signal log reactively when message logs are updated
    LaunchedEffect(messages) {
        withContext(Dispatchers.IO) {
            signalLog = viewModel.getLatestSignalLog(peerNodeId)
        }
    }

    // Update peer marker based on the latest signal health log
    LaunchedEffect(signalLog) {
        val log = signalLog
        if (log != null) {
            peerMarker.title = "Node: Peer (0x87654321)"
            peerMarker.snippet = "RSSI: ${log.rssi} dBm, SNR: ${String.format(Locale.US, "%.1f", log.snr)} dB"
            
            // Dynamic color coding: green = strong, yellow = medium, red = weak
            val markerColor = when {
                log.rssi >= -60 -> android.graphics.Color.GREEN
                log.rssi >= -75 -> android.graphics.Color.YELLOW
                else -> android.graphics.Color.RED
            }
            
            val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(48, 48)
                setColor(markerColor)
                setStroke(2, android.graphics.Color.WHITE)
            }
            peerMarker.icon = shapeDrawable
        } else {
            peerMarker.title = "Node: Peer (0x87654321)"
            peerMarker.snippet = "No Signal Data"
            val shapeDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(48, 48)
                setColor(android.graphics.Color.GRAY)
                setStroke(2, android.graphics.Color.WHITE)
            }
            peerMarker.icon = shapeDrawable
        }
        mapView.invalidate()
    }

    // Reset coordinates and camera when toggle is turned off
    LaunchedEffect(usePhoneGps) {
        if (!usePhoneGps) {
            hasCentered = false
            val defaultCenter = GeoPoint(37.7694, -122.4862)
            myMarker.position = defaultCenter
            mapView.controller.setCenter(defaultCenter)
            mapView.invalidate()
        }
    }

    // Update marker position and center map only once when location is first acquired
    LaunchedEffect(phoneLocation) {
        val loc = phoneLocation
        if (usePhoneGps && loc != null) {
            val geoPoint = GeoPoint(loc.first, loc.second)
            myMarker.position = geoPoint
            if (!hasCentered) {
                mapView.controller.setCenter(geoPoint)
                hasCentered = true
            }
            mapView.invalidate()
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onDetach()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Render View-based MapView inside Compose AndroidView
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Overlaying tactical statistics indicator box
        Card(
            colors = CardDefaults.cardColors(containerColor = StealthSurface.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .width(220.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "🛰️ LOCAL TACTICAL MAP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CryptoGreen,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "SOURCE: Offline Tile Cache",
                    fontSize = 10.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "NETWORK LINK: BLOCKED",
                    fontSize = 10.sp,
                    color = CryptoTeal,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                val currentLog = signalLog
                if (currentLog != null) {
                    Text(
                        text = "PEER RSSI: ${currentLog.rssi} dBm",
                        fontSize = 10.sp,
                        color = if (currentLog.rssi >= -75) CryptoGreen else Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "PEER SNR: ${String.format(Locale.US, "%.1f", currentLog.snr)} dB",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        text = "PEER RSSI: N/A (Offline)",
                        fontSize = 10.sp,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle phone GPS
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setUsePhoneGps(!usePhoneGps) }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = usePhoneGps,
                        onCheckedChange = { viewModel.setUsePhoneGps(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = CryptoGreen,
                            uncheckedColor = CryptoTeal,
                            checkmarkColor = Color.Black
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PHONE GPS",
                        fontSize = 10.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠️ Notice: Enabling Phone GPS requests active location info from your phone sensor.",
                    fontSize = 8.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 10.sp
                )
            }
        }
    }
}
