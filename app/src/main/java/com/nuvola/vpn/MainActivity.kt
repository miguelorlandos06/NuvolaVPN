package com.nuvola.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvola.vpn.service.TunnelVpnService
import com.nuvola.vpn.theme.NuvolaTheme
import com.nuvola.vpn.ui.HomeScreen
import com.nuvola.vpn.ui.TunnelViewModel

class MainActivity : ComponentActivity() {
    private val vm: TunnelViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startTunnelService() else vm.markError("Permiso VPN denegado")
    }

    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.markError("Notificaciones desactivadas")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            NuvolaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    HomeScreen(state = state, onConnect = ::requestVpnPermissionAndStart, onDisconnect = ::stopTunnelService)
                }
            }
        }
    }

    private fun requestVpnPermissionAndStart() {
        vm.markPreparing()
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startTunnelService()
    }

    private fun startTunnelService() {
        val svc = Intent(this, TunnelVpnService::class.java).apply { action = TunnelVpnService.ACTION_START }
        ContextCompat.startForegroundService(this, svc)
    }

    private fun stopTunnelService() {
        val svc = Intent(this, TunnelVpnService::class.java).apply { action = TunnelVpnService.ACTION_STOP }
        startService(svc)
    }
}
