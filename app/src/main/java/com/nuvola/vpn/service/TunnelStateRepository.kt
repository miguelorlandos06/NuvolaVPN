package com.nuvola.vpn.service

import android.util.Log
import com.nuvola.vpn.engine.Relay
import com.nuvola.vpn.engine.TunnelConfig
import com.nuvola.vpn.ui.TunnelStats
import com.nuvola.vpn.ui.TunnelStatus
import com.nuvola.vpn.ui.TunnelUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Puente entre el motor (Relay) y la UI.
 *
 * Singleton creado en NuvolaApp. La UI observa state; el VpnService llama
 * a attachRelay()/detachRelay() cuando el tunel arranca o se detiene.
 */
class TunnelStateRepository {

    companion object {
        private const val TAG = "NuvolaRepo"
        private const val STATS_TICK_MS = 1000L
    }

    private val _state = MutableStateFlow(TunnelUiState())
    val state: StateFlow<TunnelUiState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var statsJob: Job? = null
    private var relay: Relay? = null

    // -----------------------------------------------------------------
    // Transiciones de estado (las llama el VpnService)
    // -----------------------------------------------------------------

    fun markPreparing() {
        _state.update { it.copy(status = TunnelStatus.Preparing) }
    }

    fun markConnecting() {
        _state.update { it.copy(status = TunnelStatus.Connecting) }
    }

    fun markConnected(sinceMs: Long = System.currentTimeMillis()) {
        _state.update { it.copy(status = TunnelStatus.Connected(sinceMs)) }
        startStatsLoop()
    }

    fun markDisconnected() {
        stopStatsLoop()
        _state.update {
            it.copy(status = TunnelStatus.Disconnected, stats = TunnelStats())
        }
    }

    fun markError(message: String) {
        stopStatsLoop()
        _state.update { it.copy(status = TunnelStatus.Error(message)) }
    }

    // -----------------------------------------------------------------
    // Adjuntar el motor cuando arranca
    // -----------------------------------------------------------------

    fun attachRelay(r: Relay) {
        relay = r
        startStatsLoop()
    }

    fun detachRelay() {
        relay = null
        stopStatsLoop()
    }

    // -----------------------------------------------------------------
    // Bucle de stats
    // -----------------------------------------------------------------

    private fun startStatsLoop() {
        stopStatsLoop()
        statsJob = scope.launch {
            while (isActive) {
                val r = relay
                if (r != null) {
                    val s = TunnelStats(
                        upBytes = r.statsUpBytes,
                        downBytes = r.statsDownBytes,
                        puts = r.statsPuts,
                        gets = r.statsGets,
                        activeConns = r.activeConnCount,
                    )
                    _state.update { it.copy(stats = s) }
                }
                delay(STATS_TICK_MS)
            }
        }
    }

    private fun stopStatsLoop() {
        statsJob?.cancel()
        statsJob = null
    }

    // -----------------------------------------------------------------
    // Ciclo de vida global
    // -----------------------------------------------------------------

    fun shutdown() {
        stopStatsLoop()
        relay = null
        scope.cancel()
        Log.i(TAG, "shutdown")
    }
}
