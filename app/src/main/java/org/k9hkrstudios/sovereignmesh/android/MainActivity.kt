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

package org.k9hkrstudios.sovereignmesh.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.k9hkrstudios.sovereignmesh.android.database.MeshDatabaseHelper
import org.k9hkrstudios.sovereignmesh.android.hardware.MeshHardwareService
import org.k9hkrstudios.sovereignmesh.android.ui.DashboardScreen
import org.k9hkrstudios.sovereignmesh.android.ui.MapScreen
import org.k9hkrstudios.sovereignmesh.android.ui.SovereignViewModel
import org.k9hkrstudios.sovereignmesh.android.ui.theme.CryptoGreen
import org.k9hkrstudios.sovereignmesh.android.ui.theme.CryptoTeal
import org.k9hkrstudios.sovereignmesh.android.ui.theme.SovereignTheme
import org.k9hkrstudios.sovereignmesh.android.ui.theme.StealthBackground
import org.k9hkrstudios.sovereignmesh.android.ui.theme.StealthSurface

/**
 * MainActivity is the primary entry point for the Sovereign Mesh application.
 *
 * It manages the lifecycle of the [MeshHardwareService] connection, handles
 * hardware permissions, and hosts the main Jetpack Compose UI layout.
 */
class MainActivity : ComponentActivity() {

    private lateinit var databaseHelper: MeshDatabaseHelper
    private lateinit var viewModel: SovereignViewModel
    private var isBound = false
    private var hardwareService: MeshHardwareService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshHardwareService.LocalBinder
            val boundService = binder.getService()
            hardwareService = boundService
            isBound = true
            viewModel.setService(boundService)
            Log.d(TAG, "Successfully bound to MeshHardwareService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hardwareService = null
            isBound = false
            Log.w(TAG, "Disconnected from MeshHardwareService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        Log.d(TAG, "onCreate called")

        // 1. Initialize encrypted database and view model
        databaseHelper = MeshDatabaseHelper(this)
        viewModel = SovereignViewModel(this, databaseHelper)

        // 2. Start and bind to the foreground hardware service
        val intent = Intent(this, MeshHardwareService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 3. Request location and bluetooth permissions
        checkAndRequestHardwarePermissions()

        setContent {
            SovereignTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppLayout(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        databaseHelper.close()
        super.onDestroy()
    }

    /**
     * Verifies and requests necessary permissions for BLE scanning, device connectivity,
     * and local tactical mapping.
     */
    private fun checkAndRequestHardwarePermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Always request location permissions for the local GPS map toggle
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }
}

/**
 * The main high-level layout of the application, featuring a tactical header,
 * the active screen content, and a bottom navigation bar.
 */
@Composable
fun MainAppLayout(viewModel: SovereignViewModel) {
    var activeTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StealthBackground)
            .safeDrawingPadding()
    ) {
        // High-contrast tactical header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(StealthSurface)
                .padding(vertical = 14.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🛡️ SOVEREIGN MESH CLIENT",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CryptoGreen,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Text(
                text = "SECURE / OFF-GRID",
                fontSize = 11.sp,
                color = CryptoTeal,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Active tab rendering
        Box(modifier = Modifier.weight(1f)) {
            when (activeTab) {
                0 -> DashboardScreen(viewModel = viewModel)
                1 -> MapScreen(viewModel = viewModel)
            }
        }

        // Bottom Tab Selector Row
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = StealthSurface,
            contentColor = CryptoGreen,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                    color = CryptoGreen
                )
            }
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("DASHBOARD", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text("TACTICAL MAP", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
            )
        }
    }
}
