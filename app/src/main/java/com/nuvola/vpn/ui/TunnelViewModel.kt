package com.nuvola.vpn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.nuvola.vpn.NuvolaApp
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel de la pantalla unica.
 * Ya no tiene mock: observa el StateFlow del TunnelStateRepository.
 * connect()/disconnect() los gestiona MainActivity (permiso VPN + Intent).
 */
class TunnelViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as NuvolaApp).stateRepo

    val state: StateFlow<TunnelUiState> = repo.state

    fun markPreparing() = repo.markPreparing()

    fun markError(msg: String) = repo.markError(msg)
}
