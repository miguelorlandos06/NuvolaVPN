package com.nuvola.vpn.engine

import android.net.VpnService
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cliente HTTP hacia el S3 usando OkHttp con un SocketFactory que
 * protege cada socket con VpnService.protect() ANTES de conectar.
 * Sin esto, el trafico HTTPS al S3 entra al TUN y se produce un bucle.
 */
class Transport(
    private val vpnService: VpnService,
    base: String,
    private val bucket: String,
    userAgent: String = "NuvolaVPN/1.0",
    timeoutSec: Long = 40,
    private val maxRetries: Int = 5,
) {

    private val base = base.trimEnd('/')

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(ProtectingSocketFactory(vpnService))
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .writeTimeout(timeoutSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun url(key: String): String = "$base/$bucket/" + encodeKey(key)
    private fun listUrl(query: String): String = "$base/$bucket/?$query"

    fun put(key: String, data: ByteArray) {
        val req = Request.Builder()
            .url(url(key))
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity")
            .put(data.toRequestBody())
            .build()
        retry("PUT $key") {
            client.newCall(req).execute().use { resp ->
                if (resp.code in 200..299) return@use
                if (!isRetryable(resp.code)) throw IOException("PUT $key -> HTTP ${resp.code}")
                throw RetryableHttp(resp.code)
            }
        }
    }

    fun get(key: String): ByteArray? {
        val req = Request.Builder()
            .url(url(key))
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        return retry("GET $key") {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 200 -> return@retry resp.body?.bytes() ?: ByteArray(0)
                    resp.code == 404 -> return@retry null
                    isRetryable(resp.code) -> throw RetryableHttp(resp.code)
                    else -> throw IOException("GET $key -> HTTP ${resp.code}")
                }
            }
        }
    }

    fun list(prefix: String, startAfter: String? = null): List<String> {
        val out = ArrayList<String>()
        var token: String? = null
        var after: String? = startAfter
        var more = true
        while (more) {
            val sb = StringBuilder()
            sb.append("list-type=2&prefix=").append(encodeQuery(prefix))
            if (after != null) sb.append("&start-after=").append(encodeQuery(after))
            if (token != null) sb.append("&continuation-token=").append(encodeQuery(token))
            val reqUrl = listUrl(sb.toString())

            val req = Request.Builder()
                .url(reqUrl)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .get()
                .build()

            val xml = retry("LIST $prefix") {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 200) return@retry resp.body?.string() ?: ""
                    if (isRetryable(resp.code)) throw RetryableHttp(resp.code)
                    throw IOException("LIST $prefix -> HTTP ${resp.code}")
                }
            }
            val page = parseListXml(xml)
            out.addAll(page.keys)
            token = page.nextToken
            more = page.truncated
            after = null
        }
        return out
    }

    fun delete(key: String) {
        val req = Request.Builder()
            .url(url(key))
            .header("User-Agent", userAgent)
            .delete()
            .build()
        try { client.newCall(req).execute().close() } catch (_: Exception) {}
    }

    private fun <T> retry(label: String, block: () -> T): T {
        var lastErr: String = "desconocido"
        for (attempt in 0 until maxRetries) {
            try {
                return block()
            } catch (e: RetryableHttp) {
                lastErr = "HTTP ${e.code}"
            } catch (e: IOException) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
            try { Thread.sleep((10L..50L).random()) } catch (_: InterruptedException) {}
        }
        throw IOException("$label agoto reintentos: $lastErr")
    }

    private fun isRetryable(code: Int): Boolean =
        code in setOf(429, 500, 502, 503, 504)

    private class RetryableHttp(val code: Int) : IOException("HTTP $code")

    private class ProtectingSocketFactory(
        private val vpnService: VpnService,
    ) : SocketFactory() {
        override fun createSocket(): Socket {
            val s = Socket()
            if (!vpnService.protect(s)) {
                throw IOException("VpnService.protect() devolvio false")
            }
            return s
        }
        override fun createSocket(host: String?, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    private fun encodeKey(key: String): String =
        key.split("/").joinToString("/") { encodeQuery(it) }

    private fun encodeQuery(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code
                || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private data class ListPage(val keys: List<String>, val nextToken: String?, val truncated: Boolean)

    private fun parseListXml(xml: String): ListPage {
        val keys = ArrayList<String>()
        val regex = Regex("<Key>(.*?)</Key>", RegexOption.DOT_MATCHES_ALL)
        for (m in regex.findAll(xml)) {
            keys.add(unescapeXml(m.groupValues[1]))
        }
        val tokenMatch = Regex("<NextContinuationToken>(.*?)</NextContinuationToken>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
        val truncated = Regex("<IsTruncated>(true|false)</IsTruncated>").find(xml)
            ?.groupValues?.get(1) == "true"
        return ListPage(keys, tokenMatch?.groupValues?.get(1), truncated)
    }

    private fun unescapeXml(s: String): String =
        s.replace("&amp;", "&")
         .replace("&lt;", "<")
         .replace("&gt;", ">")
         .replace("&quot;", "\"")
         .replace("&apos;", "'")
}
