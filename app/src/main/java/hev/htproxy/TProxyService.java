package hev.htproxy;

/**
 * Clase que el .so de hev-socks5-tunnel registra via JNI_OnLoad.
 * Debe existir en el APK para que System.loadLibrary("tun2socks")
 * pueda enlazar los metodos nativos.
 */
public final class TProxyService {
    public static native boolean TProxyStartService(String config_path, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
