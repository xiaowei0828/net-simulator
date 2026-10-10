package dev.ipv6lab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import dev.ipv6lab.core.Dns64;
import dev.ipv6lab.core.DnsTransport;
import dev.ipv6lab.core.LocalGateway;
import dev.ipv6lab.core.Nat64Prefix;
import dev.ipv6lab.core.SocketProvider;
import hev.htproxy.TProxyService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class LabVpnService extends VpnService {
    public static final String STOP = "dev.ipv6lab.STOP";
    private static final int TUN_MTU = 1500;
    private final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor();
    private ConnectivityManager connectivity;
    private volatile Network physical;
    private ParcelFileDescriptor tun;
    private LocalGateway gateway;
    private boolean nativeStarted;
    private boolean callbackRegistered;
    private boolean failed;
    private boolean closing;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { refresh(); }
        @Override public void onLost(Network network) { refresh(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { refresh(); }
        @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { refresh(); }
        private void refresh() {
            if (!lifecycle.isShutdown()) {
                try { lifecycle.execute(() -> updatePhysical()); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        connectivity = getSystemService(ConnectivityManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("vpn", "IPv6 测试环境", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            lifecycle.execute(() -> { stopTunnel(); stopSelf(); });
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(64, notification("正在启动 IPv6 测试环境"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(64, notification("正在启动 IPv6 测试环境"));
        lifecycle.execute(this::startTunnel);
        return START_NOT_STICKY;
    }

    private void startTunnel() {
        if (tun != null || closing) return;
        LabState.reset(); LabState.active = true; LabState.phase = "正在启动";
        try {
            LabConfig config = LabConfig.load(this);
            LabState.log("VPN starting prefix=" + config.prefix + " scope="
                    + (config.packages.isEmpty() ? "all-apps" : config.packages + " + " + getPackageName()));
            Nat64Prefix prefix = new Nat64Prefix(config.prefix);
            updatePhysical();
            if (physical == null) throw new IOException("请先连接 Wi-Fi 或移动网络");
            SocketProvider sockets = new SocketProvider() {
                @Override public Socket tcp() throws IOException {
                    Socket socket = new Socket();
                    try {
                        // java.net.Socket creates its fd lazily on Android. Materialize
                        // it before protect(), otherwise protect reports false.
                        socket.bind(new InetSocketAddress(0));
                        if (!protect(socket)) throw new IOException("无法保护出站 TCP socket");
                        requirePhysical().bindSocket(socket); return socket;
                    } catch (IOException | RuntimeException e) { socket.close(); throw e; }
                }
                @Override public DatagramSocket udp() throws IOException {
                    DatagramSocket socket = new DatagramSocket(null);
                    try {
                        if (!protect(socket)) throw new IOException("无法保护出站 UDP socket");
                        requirePhysical().bindSocket(socket); return socket;
                    } catch (IOException | RuntimeException e) { socket.close(); throw e; }
                }
            };
            Dns64 dns = new Dns64(prefix, request -> new DnsTransport(sockets,
                    new InetSocketAddress(resolver(config), 53), message ->
                    LabState.log(message + " selectedPhysicalNetwork=" + physical)).query(request), LabState.telemetry, config.dnsOverrides);
            LabState.log("DNS overrides=" + config.dnsOverrides.size());
            String password = UUID.randomUUID().toString().replace("-", "");
            gateway = new LocalGateway(prefix, sockets, dns, LabState.telemetry, password);
            File nativeConfig = new File(getNoBackupFilesDir(), "tunnel.yml");
            String yaml = "tunnel:\n  name: ipv6lab\n  mtu: " + TUN_MTU + "\n  ipv6: '" + LocalGateway.TUN_ADDRESS + "'\n  icmp: 'off'\n"
                    + "socks5:\n  address: 127.0.0.1\n  port: " + gateway.port() + "\n  udp: 'udp'\n  username: lab\n  password: '" + password + "'\n"
                    + "misc:\n  max-session-count: 64\n  connect-timeout: 10000\n  tcp-read-write-timeout: 120000\n  udp-read-write-timeout: 30000\n  log-level: warn\n";
            try (FileOutputStream out = new FileOutputStream(nativeConfig)) { out.write(yaml.getBytes(StandardCharsets.UTF_8)); }
            Builder builder = new Builder().setSession("IPv6 Lab · DNS64/NAT64")
                    .setMtu(TUN_MTU).addAddress(LocalGateway.TUN_ADDRESS, 64).addRoute("::", 0)
                    .addDnsServer(LocalGateway.DNS_ADDRESS).setMetered(false)
                    .setUnderlyingNetworks(new Network[]{physical})
                    .setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            // No IPv4 address, DNS or route; no allowFamily(AF_INET), no allowBypass.
            // Android blocks the missing address family for every included app.
            if (!config.packages.isEmpty()) {
                for (String pkg : config.packages) builder.addAllowedApplication(pkg);
                builder.addAllowedApplication(getPackageName()); // In-app diagnostics use the same VPN.
            }
            tun = builder.establish();
            if (tun == null) throw new IOException("VPN 授权已撤销");
            nativeStarted = TProxyService.TProxyStartService(nativeConfig.getAbsolutePath(), tun.getFd());
            if (!nativeStarted) throw new IOException("用户态网络栈启动失败");
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
            callbackRegistered = true;
            LabState.phase = "运行中";
            LabState.detail = (config.packages.isEmpty() ? "全设备" : config.packages.size() + " 个指定应用")
                    + " · IPv4 已阻断 · NAT64 " + config.prefix;
            LabState.log("VPN 已建立；仅 IPv6。上游 DNS " + resolver(config).getHostAddress());
            getSystemService(NotificationManager.class).notify(64, notification("IPv4 已阻断 · DNS64/NAT64 运行中"));
            lifecycle.schedule(this::checkNative, 1, TimeUnit.SECONDS);
        } catch (Exception | LinkageError e) {
            String error = "启动失败：" + e.getMessage();
            stopTunnel(); LabState.phase = "启动失败"; LabState.detail = error; LabState.error(error, e); stopSelf();
        }
    }

    private void checkNative() {
        if (closing || tun == null || failed) return;
        if (!TProxyService.TProxyIsRunning()) {
            failed = true;
            if (gateway != null) gateway.close();
            LabState.phase = "转发异常";
            LabState.detail = "保留 VPN 接口阻断流量；请停止后重新启动。";
            LabState.error("网络栈意外退出，保留 VPN，避免静默回退到双栈。");
            getSystemService(NotificationManager.class).notify(64, notification(LabState.detail));
            return;
        }
        lifecycle.schedule(this::checkNative, 1, TimeUnit.SECONDS);
    }

    private Network requirePhysical() throws IOException {
        Network network = physical;
        if (network == null) throw new IOException("底层网络已断开");
        return network;
    }
    private InetAddress resolver(LabConfig config) throws IOException {
        if (!config.resolver.isEmpty()) return Nat64Prefix.numeric(config.resolver);
        LinkProperties props = connectivity.getLinkProperties(requirePhysical());
        if (props == null || props.getDnsServers().isEmpty()) throw new IOException("底层网络没有 DNS；请手动指定");
        // Prefer a physical IPv4 resolver, but never add it to the VPN's DNS settings.
        for (InetAddress dns : props.getDnsServers()) if (dns.getAddress().length == 4) return dns;
        return props.getDnsServers().get(0);
    }

    private void updatePhysical() {
        if (closing) return;
        Network best = null; int score = -1;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities c = connectivity.getNetworkCapabilities(network);
            if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            int candidate = (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0)
                    + (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 2 : 0)
                    + (network.equals(connectivity.getActiveNetwork()) ? 1 : 0);
            if (candidate > score) { best = network; score = candidate; }
        }
        boolean changed = physical == null ? best != null : !physical.equals(best);
        physical = best;
        if (tun != null) setUnderlyingNetworks(best == null ? new Network[0] : new Network[]{best});
        LinkProperties props = best == null ? null : connectivity.getLinkProperties(best);
        boolean v6 = false;
        if (props != null) for (android.net.RouteInfo route : props.getRoutes())
            if (route.isDefaultRoute() && route.getDestination().getAddress().getAddress().length == 16) v6 = true;
        LabState.network = best == null ? "底层网络已断开，VPN 保持阻断"
                : (props == null ? "已连接" : props.getInterfaceName()) + " · "
                + (v6 ? "检测到 IPv6 默认路由（不代表已验证可达）" : "无 IPv6 默认路由，原生 AAAA 可能不可达");
        if (changed) LabState.log("底层网络切换：" + LabState.network + " selectedPhysicalNetwork=" + best
                + " dns=" + (props == null ? "none" : props.getDnsServers()) + "；已有连接可能需重试");
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, LabVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "vpn").setSmallIcon(R.drawable.ic_network).setContentTitle("IPv6 Lab")
                .setContentText(message).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).build();
    }

    private void stopTunnel() {
        if (closing) return;
        closing = true;
        if (callbackRegistered) { connectivity.unregisterNetworkCallback(callback); callbackRegistered = false; }
        if (nativeStarted) { TProxyService.TProxyStopService(); nativeStarted = false; }
        if (gateway != null) { gateway.close(); gateway = null; }
        if (tun != null) { try { tun.close(); } catch (IOException ignored) { } tun = null; }
        LabState.active = false; LabState.phase = "已停止"; LabState.detail = "已恢复设备原有网络。";
        LabState.log("VPN stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onRevoke() {
        lifecycle.execute(() -> { LabState.log("系统已撤销 VPN 授权"); stopTunnel(); stopSelf(); });
    }
    @Override public void onDestroy() {
        lifecycle.execute(this::stopTunnel);
        lifecycle.shutdown();
        super.onDestroy();
    }
}
