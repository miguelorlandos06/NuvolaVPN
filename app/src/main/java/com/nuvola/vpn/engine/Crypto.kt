package com.nuvola.vpn.engine

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Cifrado AES-256-GCM con clave derivada del PSK vía HKDF-SHA256.
 * Layout del blob: MAGIC(3) + nonce(12) + ciphertext + tag(16).
 * AAD = ruta del objeto S3 (chan/c/<id>/<seq:012d>).
 * Nonce = 4 bytes aleatorios + 8 bytes big-endian de seq.
 *
 * Paridad byte a byte con client.js v2 y crypto.py.
 */
class Crypto(psk: String, chan: String) {

    companion object {
        val MAGIC = byteArrayOf('T'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val HDR_LEN = 3 + NONCE_LEN
        const val MIN_BLOB = HDR_LEN + TAG_LEN + 1
        private const val HKDF_INFO = "todus-relay/v1/aesgcm"
        private const val HKDF_SALT_PREFIX = "todus-relay|"

        /**
         * Implementación manual de HKDF-SHA256 (RFC 5869).
         * Extract + Expand, salida de 32 bytes.
         */
        fun hkdfSha256(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int = 32,
        ): ByteArray {
            require(length in 1..255 * 32) { "length fuera de rango" }
            val mac = Mac.getInstance("HmacSHA256")

            // Extract: PRK = HMAC(salt, ikm)
            mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            // Expand: T(1) || T(2) || ... hasta length
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var counter = 1
            while (pos < length) {
                mac.reset()
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                val toCopy = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, toCopy)
                pos += toCopy
                counter++
            }
            return out
        }
    }

    private val key: ByteArray
    private val aes: Cipher
    private val random = SecureRandom()

    init {
        val salt = (HKDF_SALT_PREFIX + chan).toByteArray(Charsets.US_ASCII)
        key = hkdfSha256(
            ikm = psk.toByteArray(Charsets.UTF_8),
            salt = salt,
            info = HKDF_INFO.toByteArray(Charsets.US_ASCII),
            length = 32,
        )
        aes = Cipher.getInstance("AES/GCM/NoPadding")
    }

    /**
     * Cifra un mensaje y devuelve el blob completo listo para PUT.
     * @param seq número de secuencia (se codifica en big-endian de 8 bytes en el nonce)
     * @param path ruta del objeto S3 (usada como AAD)
     * @param mtype tipo de mensaje (T_OPEN=1, T_DATA=2, T_FIN=3)
     * @param body payload sin comprimir
     */
    fun seal(seq: Long, path: String, mtype: Int, body: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN)
        val rnd = ByteArray(4)
        random.nextBytes(rnd)
        System.arraycopy(rnd, 0, nonce, 0, 4)
        ByteBuffer.wrap(nonce, 4, 8).putLong(seq)

        val aad = path.toByteArray(Charsets.US_ASCII)
        aes.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN * 8, nonce),
        )
        aes.updateAAD(aad)

        val plaintext = ByteArray(1 + body.size)
        plaintext[0] = (mtype and 0xFF).toByte()
        System.arraycopy(body, 0, plaintext, 1, body.size)

        val ct = aes.doFinal(plaintext) // ciphertext + tag

        val out = ByteArray(HDR_LEN + ct.size)
        System.arraycopy(MAGIC, 0, out, 0, 3)
        System.arraycopy(nonce, 0, out, 3, NONCE_LEN)
        System.arraycopy(ct, 0, out, HDR_LEN, ct.size)
        return out
    }

    /**
     * Descifra un blob. Devuelve (mtype, body).
     * Lanza CryptoError si el formato o la autenticación fallan.
     */
    fun unseal(blob: ByteArray, path: String): Pair<Int, ByteArray> {
        if (blob.size < MIN_BLOB) {
            throw CryptoError("blob demasiado corto: ${blob.size}")
        }
        if (blob[0] != MAGIC[0] || blob[1] != MAGIC[1] || blob[2] != MAGIC[2]) {
            throw CryptoError("magic invalido")
        }
        val nonce = ByteArray(NONCE_LEN)
        System.arraycopy(blob, 3, nonce, 0, NONCE_LEN)
        val ctLen = blob.size - HDR_LEN
        val ct = ByteArray(ctLen)
        System.arraycopy(blob, HDR_LEN, ct, 0, ctLen)

        val aad = path.toByteArray(Charsets.US_ASCII)
        aes.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LEN * 8, nonce),
        )
        aes.updateAAD(aad)

        val pt = try {
            aes.doFinal(ct)
        } catch (e: Exception) {
            throw CryptoError("autenticacion fallida en $path: ${e.message}", e)
        }
        if (pt.isEmpty()) throw CryptoError("plaintext vacio")
        return (pt[0].toInt() and 0xFF) to pt.copyOfRange(1, pt.size)
    }
}
