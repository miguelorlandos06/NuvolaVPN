package com.nuvola.vpn.engine

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/**
 * Motor del cliente NuvolaVPN.
 *
 * - Acepta SOCKS5 en 127.0.0.1:<port>.
 * - Por cada conexion aceptada crea un Conn y manda T_OPEN al S3.
 * - Hilo de lectura local -> DATA hacia el S3 (con semaforo inflight).
 * - Bucle de polling que LIST/GET/DELETE sobre chan/s/ y despacha DATA/FIN.
 *
 * Todo el estado del tunel vive aqui; la UI lo observa via TunnelStateRepository.
 */
class Relay(
    private val cfg: TunnelConfig,
    private val crypto: Crypto,
    private val transport: Transport,
) {
    companion object {
        private const val TAG = "NuvolaRelay"
        private const val BACKOFF_MIN_MS = 10L
        private const val BACKOFF_MAX_MS = 500L
        private const val SLEEP_AFTER_DATA_MS = 5L
    }

    private val chan = cfg.chan
    private val conns = ConcurrentHashMap<String, Conn>()
    private val connCounter = AtomicLong(0L)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var watermark: String? = null
    @Volatile private var running: Boolean = false

    @Volatile var statsUpBytes: Long = 0L
        private set
    @Volatile var statsDownBytes: Long = 0L
        private set
    @Volatile var statsPuts: Long = 0L
        private set
    @Volatile var statsGets: Long = 0L
        private set
    val activeConnCount: Int get() = conns.size

    private var pollJob: Job? = null
    private var listenJob: Job? = null
    private var listenSocket: ServerSocket? = null

    // ---------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "Relay start | chan=$chan | listen=${cfg.listen}")

        val (host, port) = parseListen(cfg.listen)
        val server = ServerSocket(port, 64, InetAddress.getByName(host))
        listenSocket = server

        listenJob = scope.launch { acceptLoop(server) }
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        try { listenSocket?.close() } catch (_: Exception) {}
        listenSocket = null
        listenJob?.cancel(); listenJob = null
        pollJob?.cancel(); pollJob = null
        for ((_, c) in conns) c.closeQuietly()
        conns.clear()
        scope.cancel()
        Log.i(TAG, "Relay stop")
    }

    // ---------------------------------------------------------------
    // SOCKS5 accept loop
    // ---------------------------------------------------------------

    private suspend fun acceptLoop(server: ServerSocket) {
        while (running && isActive) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept fallo: ${e.message}")
                continue
            }
            scope.launch { handleSocks(client) }
        }
    }

    private suspend fun handleSocks(sock: Socket) {
        try {
            if (!socks5Handshake(sock)) { sock.close(); return }
            val target = socks5ReadRequest(sock) ?: run { sock.close(); return }
            val (host, port) = target

            val connId = newConnId()
            val conn = Conn(connId, sock, cfg.inflight)
            conns[connId] = conn

            sock.getOutputStream().write(
                byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
            )
            sock.getOutputStream().flush()

            val openBody = Compress.compress(Proto.packOpen(connId, host, port))
            sendMessage(conn, Proto.T_OPEN, openBody)
            Log.i(TAG, "conn $connId -> $host:$port")

            scope.launch { localReader(conn) }
        } catch (e: Exception) {
            Log.w(TAG, "handleSocks fallo: ${e.message}")
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private suspend fun localReader(conn: Conn) {
        val input = conn.sock.getInputStream()
        val buf = ByteArray(cfg.chunkKb * 1024)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) break
                val data = if (n == buf.size) buf.copyOf() else buf.copyOfRange(0, n)
                sendMessage(conn, Proto.T_DATA, Compress.compress(data))
                statsUpBytes += data.size
            }
        } catch (_: Exception) {
        } finally {
            sendFin(conn, "fin local")
        }
    }

    private suspend fun sendMessage(conn: Conn, mtype: Int, compressedBody: ByteArray) {
        val seq = conn.nextSeq()
        val key = Proto.connKey("c", conn.id, seq)
        val blob = crypto.seal(seq, key, mtype, compressedBody)
        conn.inflight.withPermit {
            try {
                transport.put(key, blob)
                statsPuts++
            } catch (e: Exception) {
                Log.w(TAG, "send $mtype ${conn.id} fallo: ${e.message}")
                teardown(conn.id)
            }
        }
    }

    private suspend fun sendFin(conn: Conn, err: String) {
        val seq = conn.nextSeq()
        val key = Proto.connKey("c", conn.id, seq)
        val body = Compress.compress(Proto.packFin(conn.id, err))
        val blob = crypto.seal(seq, key, Proto.T_FIN, body)
        try { transport.put(key, blob); statsPuts++ } catch (_: Exception) {}
        teardown(conn.id)
    }

    private fun teardown(connId: String) {
        val c = conns.remove(connId) ?: return
        c.closeQuietly()
    }

    // ---------------------------------------------------------------
    // Polling de s/<id>/<seq>
    // ---------------------------------------------------------------

    private suspend fun pollLoop() {
        var backoff = BACKOFF_MIN_MS
        while (running && isActive) {
            val found = try {
                pollOnce()
            } catch (e: Exception) {
                Log.w(TAG, "poll error: ${e.message}")
                false
            }
            if (found) {
                backoff = BACKOFF_MIN_MS
                delay(SLEEP_AFTER_DATA_MS)
            } else {
                delay(backoff)
                backoff = minOf(backoff * 3 / 2 + 1, BACKOFF_MAX_MS)
            }
        }
    }

    private suspend fun pollOnce(): Boolean {
        val prefix = "$chan/s/"
        val keys = transport.list(prefix, watermark)
        var found = false
        for (key in keys) {
            found = true
            var nextWm: String? = null
            try {
                val blob = transport.get(key)
                if (blob != null) {
                    val (mtype, body) = crypto.unseal(blob, key)
                    val raw = Compress.decompress(body)
                    val connId = Proto.connIdOf(key)
                    when (mtype) {
                        Proto.T_DATA -> {
                            val c = conns[connId]
                            if (c != null) {
                                try { c.sock.getOutputStream().write(raw) } catch (_: Exception) {}
                            }
                        }
                        Proto.T_FIN -> {
                            val c = conns.remove(connId)
                            c?.closeQuietly()
                        }
                        else -> {}
                    }
                    statsGets++
                    statsDownBytes += raw.size
                }
                nextWm = key
            } catch (e: Exception) {
                Log.d(TAG, "procesando $key fallo: ${e.message}")
                nextWm = key
            } finally {
                try { transport.delete(key) } catch (_: Exception) {}
                if (nextWm != null) watermark = nextWm
            }
        }
        return found
    }

    // ---------------------------------------------------------------
    // SOCKS5 handshake / request
    // ---------------------------------------------------------------

    private fun socks5Handshake(sock: Socket): Boolean {
        val ins = sock.getInputStream()
        val ver = ins.read(); val n = ins.read()
        if (ver != 0x05 || n <= 0) return false
        repeat(n) { ins.read() }
        sock.getOutputStream().write(byteArrayOf(0x05, 0x00))
        sock.getOutputStream().flush()
        return true
    }

    private fun socks5ReadRequest(sock: Socket): Pair<String, Int>? {
        val ins = sock.getInputStream()
        val hdr = ByteArray(4).also { readFully(ins, it) }
        if ((hdr[0].toInt() and 0xFF) != 0x05 || (hdr[1].toInt() and 0xFF) != 0x01) return null
        val atyp = hdr[3].toInt() and 0xFF
        val host: String
        when (atyp) {
            0x01 -> {
                val a = ByteArray(4).also { readFully(ins, it) }
                host = "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
            }
            0x03 -> {
                val len = ins.read()
                if (len <= 0) return null
                val d = ByteArray(len).also { readFully(ins, it) }
                host = String(d, Charsets.UTF_8)
            }
            0x04 -> {
                val a = ByteArray(16).also { readFully(ins, it) }
                host = String(a, Charsets.ISO_8859_1)
            }
            else -> return null
        }
        val p = ByteArray(2).also { readFully(ins, it) }
        val port = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        return host to port
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n <= 0) throw IOException("EOF")
            off += n
        }
    }

    // ---------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------

    private fun newConnId(): String {
        val v = (connCounter.incrementAndGet() and 0xFFFF)
        val rnd = (0..0xFFFF).random()
        return "%04x%04x".format(v, rnd)
    }

    private fun parseListen(s: String): Pair<String, Int> {
        val idx = s.lastIndexOf(':')
        require(idx > 0) { "listen invalido: $s" }
        return s.substring(0, idx) to s.substring(idx + 1).toInt()
    }
}
