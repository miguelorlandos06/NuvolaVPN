package com.nuvola.vpn.engine

/**
 * Protocolo de mensajes sobre las claves S3.
 *   c/<conn>/<seq>  cliente->servidor: seq 0=OPEN, luego DATA y FIN
 *   s/<conn>/<seq>  servidor->cliente: DATA y FIN
 * conn = 8 hex != 00000000. seq = 12 decimales con ceros.
 *
 * Formato JSON idéntico a proto.py: sin espacios, separadores ("," ":").
 */
object Proto {

    const val T_OPEN = 1
    const val T_DATA = 2
    const val T_FIN  = 3
    const val CTL_CONN = "00000000"

    /** Construye la clave S3: "<side>/<conn>/<seq:012d>". */
    fun connKey(side: String, conn: String, seq: Long): String {
        require(seq >= 0) { "seq negativo" }
        require(conn.length == 8) { "conn debe ser 8 hex" }
        require(side == "c" || side == "s") { "side debe ser c o s" }
        return "$side/$conn/${seq.toString().padStart(12, '0')}"
    }

    /** Extrae el connId de una clave S3. Devuelve "" si no encaja. */
    fun connIdOf(key: String): String {
        val parts = key.split("/")
        if (parts.size < 4) return ""
        val conn = parts[parts.size - 2]
        return if (conn.length == 8 && conn.all { it in "0123456789abcdef" }) conn else ""
    }

    /** T_OPEN: {"id":"...","host":"...","port":N} */
    fun packOpen(conn: String, host: String, port: Int): ByteArray {
        require(port in 1..65535) { "puerto fuera de rango" }
        val json = "{\"id\":\"$conn\",\"host\":${jsonString(host)},\"port\":$port}"
        return json.toByteArray(Charsets.UTF_8)
    }

    /** T_FIN: {"id":"...","err":"..."} */
    fun packFin(conn: String, err: String = ""): ByteArray {
        val json = "{\"id\":\"$conn\",\"err\":${jsonString(err)}}"
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Parser JSON mínimo para T_OPEN y T_FIN.
     * Acepta exactamente el formato que emite proto.py.
     */
    fun unpackJson(body: ByteArray): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        val s = body.toString(Charsets.UTF_8)
        if (!s.startsWith("{") || !s.endsWith("}")) return emptyMap()
        val out = HashMap<String, String>(4)
        var i = 1
        val n = s.length - 1
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) break
            if (s[i] != '"') return emptyMap()
            val keyEnd = s.indexOf('"', i + 1)
            if (keyEnd < 0 || keyEnd >= n) return emptyMap()
            val key = s.substring(i + 1, keyEnd)
            i = keyEnd + 1
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != ':') return emptyMap()
            i++
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) return emptyMap()
            if (s[i] == '"') {
                val valEnd = findStringEnd(s, i + 1)
                if (valEnd < 0) return emptyMap()
                out[key] = unescape(s.substring(i + 1, valEnd))
                i = valEnd + 1
            } else {
                val start = i
                while (i < n && (s[i].isDigit() || s[i] == '-')) i++
                out[key] = s.substring(start, i)
            }
            while (i < n && s[i].isWhitespace()) i++
            if (i < n && s[i] == ',') i++
        }
        return out
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"'  -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u").append("%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun findStringEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"'  -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"'  -> { sb.append('"');  i += 2 }
                    'n'  -> { sb.append('\n'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    'b'  -> { sb.append('\b'); i += 2 }
                    'f'  -> { sb.append('\u000C'); i += 2 }
                    'u'  -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toInt(16)
                            sb.append(code.toChar())
                            i += 6
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
