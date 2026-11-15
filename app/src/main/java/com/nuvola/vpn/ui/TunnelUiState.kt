package com.nuvola.vpn.ui

sealed interface TunnelStatus {
    data object Disconnected : TunnelStatus
    data object Preparing    : TunnelStatus
    data object Connecting   : TunnelStatus
    data class  Connected(val sinceMs: Long) : TunnelStatus
    data class  Error(val message: String)   : TunnelStatus
}

data class TunnelStats(
    val upBytes: Long = 0,
    val downBytes: Long = 0,
    val puts: Long = 0,
    val gets: Long = 0,
    val activeConns: Int = 0,
)

data class TunnelUiState(
    val status: TunnelStatus = TunnelStatus.Disconnected,
    val stats: TunnelStats = TunnelStats(),
)
