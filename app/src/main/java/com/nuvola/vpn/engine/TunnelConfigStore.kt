package com.nuvola.vpn.engine

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TunnelConfigStore(private val context: Context) {

    companion object {
        private const val TAG = "NuvolaConfig"
        private const val ASSET_NAME = "config.json"
    }

    private val _config = MutableStateFlow(TunnelConfig())
    val config: StateFlow<TunnelConfig> = _config.asStateFlow()

    suspend fun load(): TunnelConfig = withContext(Dispatchers.IO) {
        val cfg = try {
            parse(readAsset(ASSET_NAME))
        } catch (e: Exception) {
            Log.e(TAG, "error leyendo $ASSET_NAME: ${e.message}")
            TunnelConfig()
        }
        _config.value = cfg
        Log.i(TAG, "config cargada: chan=${cfg.chan.take(8)}… valid=${cfg.isValid()} chunkKb=${cfg.chunkKb} pollMs=${cfg.pollMs}")
        cfg
    }

    fun override(cfg: TunnelConfig) {
        _config.value = cfg
    }

    private fun readAsset(name: String): String {
        return try {
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw IOException("asset $name no encontrado", e)
        }
    }

    private fun parse(json: String): TunnelConfig {
        val root = JSONObject(json)
        val obj = root.optJSONObject("todus_relay") ?: root
        return TunnelConfig(
            s3Base = obj.optString("s3_base", TunnelConfig.DEFAULT_S3_BASE),
            bucket = obj.optString("bucket", TunnelConfig.DEFAULT_BUCKET),
            chan = obj.optString("chan", ""),
            psk = obj.optString("psk", ""),
            chunkKb = obj.optInt("chunk_kb", 256),
            pollMs = obj.optInt("poll_ms", 50),
            workers = obj.optInt("workers", 8),
            inflight = obj.optInt("inflight", 64),
            compress = obj.optBoolean("compress", true),
            listen = obj.optString("listen", TunnelConfig.DEFAULT_LISTEN),
            dialTimeoutS = obj.optInt("dial_timeout_s", 30),
        )
    }
}
