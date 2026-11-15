package com.nuvola.vpn.engine

import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compresion zlib con flag en el primer byte:
 *   0x00 = sin compresion
 *   0x01 = zlib
 * (0x02 = zstd en Python, pero NO lo emitimos: server.js no lo soporta)
 *
 * Identico a compress.py en lo que importa para interoperar:
 *   - Payloads < 256 bytes se envian sin comprimir (flag 0x00).
 *   - Si zlib no reduce, se envia sin comprimir (flag 0x00).
 */
object Compress {

    const val MIN_SIZE = 256
    const val LEVEL = 6
    const val MAX_DECOMPRESSED = 16 * 1024 * 1024

    fun compress(body: ByteArray): ByteArray {
        if (body.size < MIN_SIZE) return byteArrayOf(0x00) + body
        val deflater = Deflater(LEVEL)
        try {
            deflater.setInput(body)
            deflater.finish()
            val buf = ByteArray(body.size + 64)
            val n = deflater.deflate(buf)
            if (n <= 0 || n >= body.size) return byteArrayOf(0x00) + body
            return byteArrayOf(0x01) + buf.copyOf(n)
        } finally {
            deflater.end()
        }
    }

    fun decompress(blob: ByteArray): ByteArray {
        if (blob.isEmpty()) return blob
        return when (val flag = blob[0].toInt() and 0xFF) {
            0x00 -> blob.copyOfRange(1, blob.size)
            0x01 -> inflate(blob, 1, blob.size - 1)
            0x02 -> throw IllegalArgumentException("zstd no soportado en NuvolaVPN")
            else -> throw IllegalArgumentException("flag de compresion desconocido: $flag")
        }
    }

    private fun inflate(src: ByteArray, offset: Int, length: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(src, offset, length)
            val out = ArrayList<ByteArray>()
            var total = 0
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) {
                    if (inflater.needsInput()) break
                    if (inflater.needsDictionary()) throw IllegalStateException("zlib necesita diccionario")
                }
                total += n
                if (total > MAX_DECOMPRESSED) {
                    throw IllegalStateException("zlib output excede $MAX_DECOMPRESSED")
                }
                out.add(chunk.copyOf(n))
            }
            val result = ByteArray(total)
            var pos = 0
            for (b in out) {
                System.arraycopy(b, 0, result, pos, b.size)
                pos += b.size
            }
            return result
        } finally {
            inflater.end()
        }
    }
}
