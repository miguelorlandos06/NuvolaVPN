package com.nuvola.vpn.engine

/**
 * Clase que el .so de hev-socks5-tunnel registrará via JNI_OnLoad.
 * El PKGNAME en el workflow (com/nuvola/vpn/engine) apunta a este paquete.
 * Los métodos nativos se registran al cargar la librería.
 */
object TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray
}
