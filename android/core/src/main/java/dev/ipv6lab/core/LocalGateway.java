package dev.ipv6lab.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback SOCKS5 endpoint used only by the embedded tun2socks stack. */
public final class LocalGateway implements Closeable {
    private static final AtomicLong UDP_FLOW_IDS = new AtomicLong();
    public static final String DNS_ADDRESS = "fd64:64:64:1::53";
    public static final String TUN_ADDRESS = "fd64:64:64:1::2";
    private final Nat64Prefix prefix;
    private final SocketProvider sockets;
    private final Dns64 dns;
    private final Telemetry stats;
    private final String password;
    private final ServerSocket listener;
    private final Set<Closeable> resources = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 192, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "ipv6-gateway"); t.setDaemon(true); return t; });
    private volatile boolean closed;

    public LocalGateway(Nat64Prefix prefix, SocketProvider sockets, Dns64 dns, Telemetry stats, String password) throws IOException {
        this.prefix = prefix; this.sockets = sockets; this.dns = dns; this.stats = stats; this.password = password;
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(Nat64Prefix.numeric("127.0.0.1"), 0), 64);
        resources.add(listener);
        workers.execute(this::accept);
    }

    public int port() { return listener.getLocalPort(); }

    private <T extends Closeable> T track(T resource) throws IOException {
        resources.add(resource);
        if (closed) { release(resource); throw new IOException("Gateway stopped"); }
        return resource;
    }
    private void release(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (IOException ignored) { }
        resources.remove(resource);
    }
    private void accept() {
        while (!closed) {
            Socket client = null;
            try {
                client = track(listener.accept());
                Socket accepted = client;
                workers.execute(() -> handle(accepted));
            } catch (IOException | RejectedExecutionException e) {
                release(client);
                if (!closed) stats.error("本地代理连接失败：" + e.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            if (!authenticate(in, out)) return;
            if (in.readUnsignedByte() != 5) throw new IOException("SOCKS version");
            int command = in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) throw new IOException("SOCKS reserved byte");
            Endpoint destination = Endpoint.read(in);
            if (command == 1) {
                if (destination.address.getAddress().length != 16) {
                    stats.rejected.incrementAndGet(); reply(out, 8, 0); return;
                }
                if (isDns(destination)) { reply(out, 0, 0); serveDns(in, out); }
                else connect(client, destination, out);
            } else if (command == 3) associate(client, in, out);
            else reply(out, 7, 0);
        } catch (java.io.EOFException ignored) {
            // Normal client/control-connection shutdown, including DNS-over-TCP.
        } catch (IOException | IllegalArgumentException | RejectedExecutionException e) {
            if (!closed) stats.error("连接结束：" + e.getMessage());
        } finally { release(client); }
    }

    private boolean authenticate(DataInputStream in, DataOutputStream out) throws IOException {
        if (in.readUnsignedByte() != 5) return false;
        int count = in.readUnsignedByte(); boolean supported = false;
        for (int i = 0; i < count; i++) supported |= in.readUnsignedByte() == 2;
        out.write(new byte[]{5, (byte) (supported ? 2 : 255)}); out.flush();
        if (!supported || in.readUnsignedByte() != 1) return false;
        byte[] username = new byte[in.readUnsignedByte()]; in.readFully(username);
        byte[] secret = new byte[in.readUnsignedByte()]; in.readFully(secret);
        boolean ok = Arrays.equals(username, "lab".getBytes(StandardCharsets.US_ASCII))
                && MessageDigest.isEqual(secret, password.getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[]{1, (byte) (ok ? 0 : 1)}); out.flush();
        return ok;
    }

    private void serveDns(DataInputStream in, DataOutputStream out) throws IOException {
        while (!closed) {
            int length = in.readUnsignedShort();
            if (length < 12) throw new IOException("Short DNS query");
            byte[] query = new byte[length]; in.readFully(query);
            byte[] response = dns.answer(query, true);
            out.writeShort(response.length); out.write(response); out.flush();
        }
    }

    private InetAddress translate(Endpoint endpoint) throws IOException {
        if (endpoint.address.getAddress().length != 16) {
            stats.rejected.incrementAndGet(); throw new IOException("Direct IPv4 blocked");
        }
        if (endpoint.port == 0 || endpoint.address.isAnyLocalAddress() || endpoint.address.isMulticastAddress()
                || endpoint.address.isLinkLocalAddress()) throw new IOException("Unsupported destination");
        InetAddress address = prefix.translate(endpoint.address);
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) throw new IOException("Unsupported translated destination");
        if (prefix.contains(endpoint.address)) stats.translated.incrementAndGet();
        else stats.nativeIpv6.incrementAndGet();
        stats.log((prefix.contains(endpoint.address) ? "NAT64 " : "IPv6 ") + address.getHostAddress() + ":" + endpoint.port);
        return address;
    }

    private void connect(Socket client, Endpoint endpoint, DataOutputStream out) throws IOException {
        InetAddress target = translate(endpoint);
        Socket remote = track(sockets.tcp());
        try {
            try { remote.connect(new InetSocketAddress(target, endpoint.port), 8000); }
            catch (IOException e) { reply(out, 4, 0); throw e; }
            remote.setSoTimeout(120000); remote.setTcpNoDelay(true); client.setSoTimeout(120000);
            reply(out, 0, 0);
            java.util.concurrent.Future<?> incoming = workers.submit(() -> {
                try { pump(remote.getInputStream(), client.getOutputStream(), false); client.shutdownOutput(); }
                catch (IOException ignored) { release(client); }
            });
            try {
                pump(client.getInputStream(), remote.getOutputStream(), true);
                remote.shutdownOutput();
                // Preserve TCP half-close: response may arrive after the request's FIN.
                incoming.get(125, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { incoming.cancel(true); }
        } finally { release(remote); }
    }

    private void pump(InputStream in, OutputStream out, boolean upload) throws IOException {
        byte[] bytes = new byte[16384]; int n;
        while (!closed && (n = in.read(bytes)) != -1) {
            out.write(bytes, 0, n);
            (upload ? stats.sent : stats.received).addAndGet(n);
        }
    }

    private void associate(Socket control, DataInputStream in, DataOutputStream out) throws IOException {
        DatagramSocket relay = track(new DatagramSocket(new InetSocketAddress("127.0.0.1", 0)));
        Association association = new Association(relay);
        try {
            workers.execute(association::run);
            reply(out, 0, relay.getLocalPort());
            control.setSoTimeout(0);
            while (in.read() != -1 && !closed) { /* Lifetime is the authenticated control connection. */ }
        } finally { association.close(); release(relay); }
    }

    private final class Association implements Closeable {
        private final DatagramSocket relay;
        private final Map<Endpoint, UdpFlow> flows = new ConcurrentHashMap<>();
        private InetSocketAddress peer;
        private volatile boolean stopped;
        Association(DatagramSocket relay) { this.relay = relay; }
        void run() {
            byte[] buffer = new byte[65535];
            while (!closed && !stopped) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length); relay.receive(packet);
                    if (!packet.getAddress().isLoopbackAddress()) continue;
                    InetSocketAddress sender = (InetSocketAddress) packet.getSocketAddress();
                    if (peer != null && !peer.equals(sender)) continue;
                    DataInputStream input = new DataInputStream(new ByteArrayInputStream(buffer, 0, packet.getLength()));
                    if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) continue; // No SOCKS fragmentation.
                    Endpoint destination = Endpoint.read(input);
                    if (destination.address.getAddress().length != 16) { stats.rejected.incrementAndGet(); continue; }
                    byte[] payload = new byte[input.available()]; input.readFully(payload);
                    if (peer == null) peer = sender;
                    if (isDns(destination)) {
                        workers.execute(() -> {
                            try { send(destination, dns.answer(payload, false)); }
                            catch (IOException e) { if (!stopped && !closed) stats.error("DNS UDP：" + e.getMessage()); }
                        });
                        continue;
                    }
                    UdpFlow flow = flows.get(destination);
                    if (flow == null) {
                        if (flows.size() >= 32) throw new IOException("UDP flow limit (32 per association)");
                        InetAddress target = translate(destination);
                        flow = new UdpFlow(destination, target);
                        try {
                            flow.remote = track(sockets.udp());
                            flow.remote.connect(new InetSocketAddress(target, destination.port)); flow.remote.setSoTimeout(30000);
                            flows.put(destination, flow);
                            if (stopped || closed) throw new IOException("Association stopped");
                            UdpFlow connected = flow;
                            workers.execute(connected::receive);
                        } catch (IOException | RuntimeException e) {
                            flow.fail("setup", e); flow.finish("setup-error"); continue;
                        }
                    }
                    try { flow.sendOutbound(payload); }
                    catch (IOException e) { flow.fail("send", e); flow.finish("send-error"); }
                } catch (IOException | IllegalArgumentException | RejectedExecutionException e) {
                    if (!closed && !stopped) stats.error("UDP：" + e.getMessage());
                    if (relay.isClosed()) break;
                }
            }
        }
        private final class UdpFlow {
            final Endpoint destination;
            final long started = System.nanoTime();
            final String context;
            DatagramSocket remote;
            long txPackets, txBytes, rxPackets, rxBytes, relayPackets, relayBytes;
            long lastProgress = started;
            boolean finished;
            UdpFlow(Endpoint destination, InetAddress target) {
                this.destination = destination;
                context = "UDP flow=" + UDP_FLOW_IDS.incrementAndGet()
                        + " virtual=[" + destination.address.getHostAddress() + "]:" + destination.port
                        + " remote=[" + target.getHostAddress() + "]:" + destination.port;
                stats.log(context + " event=open");
            }
            synchronized void sendOutbound(byte[] payload) throws IOException {
                if (finished) throw new IOException("UDP flow closed");
                remote.send(new DatagramPacket(payload, payload.length));
                txPackets++; txBytes += payload.length; stats.sent.addAndGet(payload.length);
                if (txPackets == 1) log("first-send bytes=" + payload.length + " local=" + remote.getLocalSocketAddress());
                progress();
            }
            synchronized void received(byte[] payload) throws IOException {
                if (finished) return;
                rxPackets++; rxBytes += payload.length;
                if (rxPackets == 1) log("first-receive bytes=" + payload.length);
                send(destination, payload);
                relayPackets++; relayBytes += payload.length; stats.received.addAndGet(payload.length);
                if (relayPackets == 1) log("first-relay bytes=" + payload.length);
                progress();
            }
            void receive() {
                String reason = "association-close";
                String phase = "receive";
                try {
                    byte[] buffer = new byte[65507];
                    while (!closed && !stopped) {
                        phase = "receive";
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length); remote.receive(packet);
                        phase = "relay";
                        received(Arrays.copyOf(buffer, packet.getLength()));
                    }
                } catch (SocketTimeoutException e) { reason = "receive-idle-timeout-30s"; }
                catch (IOException e) { reason = phase + "-error"; fail(phase, e); }
                finally { finish(reason); }
            }
            synchronized void fail(String phase, Exception e) {
                if (!finished && !closed && !stopped) {
                    stats.error(context + " event=error phase=" + phase + " exception="
                            + e.getClass().getSimpleName() + " message=" + e.getMessage() + counters());
                }
            }
            synchronized void finish(String reason) {
                if (finished) return;
                finished = true;
                flows.remove(destination, this);
                release(remote);
                log("close reason=" + (closed ? "gateway-stop" : stopped ? "association-close" : reason));
            }
            private void progress() {
                long now = System.nanoTime();
                if (now - lastProgress >= TimeUnit.SECONDS.toNanos(1)) {
                    lastProgress = now; log("progress");
                }
            }
            private void log(String event) { stats.log(context + " event=" + event + counters()); }
            private String counters() {
                return " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        + " txPackets=" + txPackets + " txBytes=" + txBytes
                        + " rxPackets=" + rxPackets + " rxBytes=" + rxBytes
                        + " relayPackets=" + relayPackets + " relayBytes=" + relayBytes;
            }
        }
        void send(Endpoint source, byte[] payload) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 22);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(0); out.writeByte(0); source.write(out); out.write(payload);
            byte[] response = bytes.toByteArray();
            relay.send(new DatagramPacket(response, response.length, peer));
        }
        @Override public void close() {
            stopped = true; relay.close();
            for (UdpFlow flow : flows.values()) flow.finish("association-close");
            flows.clear();
        }
    }

    private static boolean isDns(Endpoint endpoint) {
        return endpoint.port == 53 && endpoint.address.equals(Nat64Prefix.numeric(DNS_ADDRESS));
    }
    private static void reply(DataOutputStream out, int code, int port) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 127, 0, 0, 1}); out.writeShort(port); out.flush();
    }
    @Override public void close() {
        closed = true;
        for (Closeable resource : resources) release(resource);
        workers.shutdownNow();
    }

    private static final class Endpoint {
        final InetAddress address; final int port;
        Endpoint(InetAddress address, int port) { this.address = address; this.port = port; }
        static Endpoint read(DataInputStream in) throws IOException {
            int type = in.readUnsignedByte();
            if (type != 1 && type != 4) throw new IOException("Numeric IP required; domain proxying disabled");
            byte[] address = new byte[type == 1 ? 4 : 16]; in.readFully(address);
            return new Endpoint(Nat64Prefix.fromBytes(address), in.readUnsignedShort());
        }
        void write(DataOutputStream out) throws IOException {
            byte[] bytes = address.getAddress(); out.writeByte(bytes.length == 4 ? 1 : 4); out.write(bytes); out.writeShort(port);
        }
        @Override public int hashCode() { return address.hashCode() * 31 + port; }
        @Override public boolean equals(Object o) {
            return o instanceof Endpoint && port == ((Endpoint)o).port && address.equals(((Endpoint)o).address);
        }
    }
}
