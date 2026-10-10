package hev.htproxy;

/** Must match the upstream JNI registration contract. */
public final class TProxyService {
    private TProxyService() {}
    static { System.loadLibrary("hev-socks5-tunnel"); }
    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
