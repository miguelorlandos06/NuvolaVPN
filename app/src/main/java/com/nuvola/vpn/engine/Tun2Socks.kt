package com.nuvola.vpn.engine

import android.util.Log
import java.io.FileDescriptor
import hev.htproxy.TProxyService

/**
 * Wrapper sobre el AAR precompilado de hev-socks5-tunnel.
 * La clase hev.htproxy.TProxyService viene incluida en el AAR
 * y tiene los metodos nativos ya registrados (JNI_OnLoad).
 */
object Tun2Socks {

    private const val TAG = "NuvolaTun2Socks"

    @Volatile private var running = false

    fun start(tunFd: FileDescriptor, yamlPath: String): Boolean {
        if (running) {
            Log.w(TAG, "ya estaba corriendo")
            return true
        }
        val fdInt = fdToInt(tunFd)
        if (fdInt < 0) {
            Log.e(TAG, "fd invalido")
            return false
        }
        return try {
            val ok = TProxyService.TProxyStartService(yamlPath, fdInt)
            if (ok) {
                running = true
                Log.i(TAG, "tun2socks start ok (fd=$fdInt)")
            } else {
                Log.e(TAG, "TProxyStartService devolvio false")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "start excepcion: ${t.message}", t)
            false
        }
    }

    fun stop() {
        if (!running) return
        try {
            TProxyService.TProxyStopService()
            Log.i(TAG, "tun2socks stop")
        } catch (t: Throwable) {
            Log.w(TAG, "stop excepcion: ${t.message}")
        } finally {
            running = false
        }
    }

    fun stats(): LongArray? {
        if (!running) return null
        return try {
            TProxyService.TProxyGetStats()
        } catch (_: Throwable) {
            null
        }
    }

    val isRunning: Boolean get() = running

    @Suppress("PrivateApi")
    private fun fdToInt(fd: FileDescriptor): Int {
        return try {
            val f = FileDescriptor::class.java.getDeclaredField("descriptor")
            f.isAccessible = true
            f.getInt(fd)
        } catch (t: Throwable) {
            Log.e(TAG, "no se pudo leer fd: ${t.message}")
            -1
        }
    }
}
