package com.nuvola.vpn.service

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nuvola.vpn.NuvolaApp
import com.nuvola.vpn.R
import com.nuvola.vpn.engine.Crypto
import com.nuvola.vpn.engine.Relay
import com.nuvola.vpn.engine.Transport
import com.nuvola.vpn.engine.Tun2Socks
import com.nuvola.vpn.engine.TunnelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

class TunnelVpnService : VpnService() {
    companion object {
        private const val TAG = "NuvolaVpn"
        const val ACTION_START = "com.nuvola.vpn.action.START"
        const val ACTION_STOP  = "com.nuvola.vpn.action.STOP"
        private const val VIRTUAL_IP = "198.18.0.1"
        private const val VIRTUAL_DNS = "198.18.0.2"
        private const val VIRTUAL_PREFIX = 24
        private const val MTU = 1500
        private const val YAML_NAME = "hev-socks5-tunnel.yml"
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var relay: Relay? = null
    private var scope: CoroutineScope? = null
    private var notifJob: Job? = null
    private var running: Boolean = false

    private val repo: TunnelStateRepository
        get() = (application as NuvolaApp).stateRepo

    private val configStore: com.nuvola.vpn.engine.TunnelConfigStore
        get() = (application as NuvolaApp).configStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(); return START_NOT_STICKY }
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() { stopTunnel(); super.onDestroy() }
    override fun onRevoke() { Log.w(TAG, "VPN revocada"); stopTunnel(); super.onRevoke() }

    private fun startTunnel() {
        if (running) return
        running = true
        repo.markConnecting()
        startForeground(NotificationHelper.NOTIF_ID, NotificationHelper.build(this, getString(R.string.notif_connecting)))
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = sc
        sc.launch {
            try { bootTunnel() } catch (t: Throwable) {
                Log.e(TAG, "bootTunnel fallo: ${t.message}", t)
                repo.markError(t.message ?: "error desconocido")
                stopTunnel()
            }
        }
    }

    private suspend fun bootTunnel() {
        val cfg = configStore.load()
        if (!cfg.isValid()) { repo.markError("config invalida"); stopTunnel(); return }

        val builder = Builder()
            .setSession("NuvolaVPN").setMtu(MTU)
            .addAddress(VIRTUAL_IP, VIRTUAL_PREFIX).addRoute("0.0.0.0", 0)
            .addDnsServer(VIRTUAL_DNS).setBlocking(false)
        
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        
        val fd = builder.establish() ?: run { repo.markError("TUN null"); stopTunnel(); return }
        tunFd = fd

        val yamlPath = writeYaml(cfg)
        val crypto = Crypto(cfg.psk, cfg.chan)
        val transport = Transport(this, cfg.s3Base, cfg.bucket)
        
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val devHash = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray()).take(2).joinToString("") { "%02x".format(it) }

        val r = Relay(cfg, crypto, transport, devHash)
        r.start()
        relay = r
        repo.attachRelay(r)

        val ok = Tun2Socks.start(fd.fileDescriptor, yamlPath)
        if (!ok) {
            r.stop(); relay = null; repo.detachRelay()
            repo.markError("tun2socks fallo"); stopTunnel(); return
        }

        repo.markConnected()
        NotificationHelper.update(this, getString(R.string.notif_connected))
        startNotifLoop()
    }

    private fun stopTunnel() {
        if (!running) return
        running = false
        notifJob?.cancel(); notifJob = null
        try { Tun2Socks.stop() } catch (_: Exception) {}
        try { relay?.stop() } catch (_: Exception) {}
        relay = null
        try { scope?.cancel() } catch (_: Exception) {}
        scope = null
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        repo.detachRelay()
        repo.markDisconnected()
        NotificationHelper.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun writeYaml(cfg: TunnelConfig): String {
        val file = File(filesDir, YAML_NAME)
        val socksPort = cfg.listen.substringAfterLast(':').toIntOrNull() ?: 1080
        val yaml = """
            tunnel:
              name: tun0
              mtu: $MTU
              ipv4: $VIRTUAL_IP
              ipv6: 'fc00::1'
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'udp'
              pipeline: false
            mapdns:
              address: $VIRTUAL_DNS
              port: 53
              network: 240.0.0.0
              netmask: 240.0.0.0
              cache-size: 10000
            misc:
              task-stack-size: 20480
              connect-timeout: ${cfg.dialTimeoutS * 1000}
              read-write-timeout: 60000
              log-file: stderr
              log-level: warn
        """.trimIndent()
        file.writeText(yaml)
        return file.absolutePath
    }

    private fun startNotifLoop() {
        notifJob?.cancel()
        val sc = scope ?: return
        notifJob = sc.launch {
            while (isActive) {
                delay(2000)
                val s = relay ?: break
                val text = "↑ ${formatBytes(s.statsUpBytes)}   ↓ ${formatBytes(s.statsDownBytes)}   ${s.activeConnCount} conns"
                NotificationHelper.update(this@TunnelVpnService, text)
            }
        }
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    }
}
