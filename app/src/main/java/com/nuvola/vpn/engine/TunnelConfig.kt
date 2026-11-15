package com.nuvola.vpn.engine

/**
 * Configuracion del motor NuvolaVPN.
 * Mismos campos y valores por defecto que config.client.json
 * (ajustados a movil: poll_ms=100, workers=8, inflight=32).
 */
data class TunnelConfig(
    val s3Base: String = "https://s3.todus.cu",
    val bucket: String = "stickers",
    val chan: String = "",
    val psk: String = "",
    val chunkKb: Int = 32,
    val pollMs: Int = 100,
    val workers: Int = 8,
    val inflight: Int = 32,
    val compress: Boolean = true,
    val listen: String = "127.0.0.1:1080",
) {
    fun isValid(): Boolean =
        chan.matches(Regex("^[0-9a-f]{16}$")) &&
        psk.length >= 16 &&
        chunkKb in 8..4096 &&
        inflight in 1..256 &&
        pollMs in 10..5000 &&
        listen.contains(':')

    companion object {
        const val DEFAULT_S3_BASE = "https://s3.todus.cu"
        const val DEFAULT_BUCKET = "stickers"
        const val DEFAULT_LISTEN = "127.0.0.1:1080"
    }
}
