package com.nuvola.vpn.engine

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore

/**
 * Estado de una conexion SOCKS5 local en el cliente.
 * - id: 8 hex unico por conexion (generado por Relay)
 * - sock: socket local aceptado por el SOCKS5 (habla con la app)
 * - seq: contador atomico de secuencia para c/<id>/<seq>
 * - inflight: semaforo que limita PUT concurrentes hacia el S3
 * - closed: evita dobles teardowns
 */
class Conn(
    val id: String,
    val sock: Socket,
    inflightPermits: Int,
) {
    val seq = AtomicLong(0L)
    val inflight = Semaphore(inflightPermits)
    val closed = AtomicBoolean(false)

    fun nextSeq(): Long = seq.getAndIncrement()

    fun markClosed(): Boolean = closed.compareAndSet(false, true)

    fun closeQuietly() {
        if (markClosed()) {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
