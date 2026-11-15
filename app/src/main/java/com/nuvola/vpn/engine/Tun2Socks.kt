package com.nuvola.vpn.engine

import android.util.Log
import java.io.FileDescriptor

object Tun2Socks {
    init { System.loadLibrary("hev-socks5-tunnel") }
    private const val TAG = "NuvolaTun2Socks"
    @Volatile private var running = false

    fun start(tunFd: FileDescriptor, yamlPath: String): Boolean {
        if (running) return true
        val fdInt = fdToInt(tunFd)
        if (fdInt < 0) return false
        return try {
            TProxyService.TProxyStartService(yamlPath, fdInt)
            running = true
            Log.i(TAG, "tun2socks start ok")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "start fallo: ${t.message}", t)
            false
        }
    }

    fun stop() {
        if (!running) return
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        running = false
    }

    fun stats(): LongArray? {
        if (!running) return null
        return try { TProxyService.TProxyGetStats() } catch (_: Throwable) { null }
    }

    @Suppress("PrivateApi")
    private fun fdToInt(fd: FileDescriptor): Int = try {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        f.getInt(fd)
    } catch (t: Throwable) { -1 }
}
