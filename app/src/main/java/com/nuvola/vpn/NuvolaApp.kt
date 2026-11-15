package com.nuvola.vpn

import android.app.Application
import android.util.Log
import com.nuvola.vpn.engine.TunnelConfigStore
import com.nuvola.vpn.service.TunnelStateRepository

/**
 * Application de NuvolaVPN.
 * Crea los singletons que comparten UI y VpnService:
 *  - TunnelConfigStore: lee assets/config.json
 *  - TunnelStateRepository: StateFlow del estado y stats del tunel
 */
class NuvolaApp : Application() {

    companion object {
        private const val TAG = "NuvolaApp"
    }

    lateinit var configStore: TunnelConfigStore
        private set
    lateinit var stateRepo: TunnelStateRepository
        private set

    override fun onCreate() {
        super.onCreate()
        configStore = TunnelConfigStore(this)
        stateRepo = TunnelStateRepository()
        Log.i(TAG, "NuvolaApp inicializada")
    }
}
