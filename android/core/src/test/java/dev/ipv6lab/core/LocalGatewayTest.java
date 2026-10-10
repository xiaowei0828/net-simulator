package dev.ipv6lab.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class LocalGatewayTest {
    private final ExecutorService server = Executors.newCachedThreadPool();
    private final Nat64Prefix prefix = new Nat64Prefix(Nat64Prefix.DEFAULT);
    private LocalGateway gateway;
    private final BlockingQueue<String> logs = new LinkedBlockingQueue<>();
    private final String password = "test-only-secret";
    @Before public void start() throws Exception {
        Telemetry stats = new Telemetry(logs::add);
        SocketProvider sockets = new SocketProvider() {
            public Socket tcp() { return new Socket(); }
            public DatagramSocket udp() throws IOException { return new DatagramSocket(); }
        };
        gateway = new LocalGateway(prefix, sockets, new Dns64(prefix, q -> { throw new IOException("unused"); }, stats), stats, password);
    }
    @After public void stop() { gateway.close(); server.shutdownNow(); }
    private String awaitLog(String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            String line = logs.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line != null && line.contains(marker)) return line;
        }
        throw new AssertionError("Missing log: " + marker);
    }
    private Socket authenticated() throws Exception {
        Socket s = new Socket("127.0.0.1", gateway.port()); s.setSoTimeout(4000);
        DataInputStream in = new DataInputStream(s.getInputStream()); DataOutputStream out = new DataOutputStream(s.getOutputStream());
        out.write(new byte[]{5, 1, 2}); assertEquals(5, in.readUnsignedByte()); assertEquals(2, in.readUnsignedByte());
        out.writeByte(1); out.writeByte(3); out.writeBytes("lab"); out.writeByte(password.length()); out.writeBytes(password);
        assertEquals(1, in.readUnsignedByte()); assertEquals(0, in.readUnsignedByte()); return s;
    }
    private int request(Socket s, int command, InetAddress address, int port) throws Exception {
        DataOutputStream out = new DataOutputStream(s.getOutputStream()); byte[] raw = address.getAddress();
        out.write(new byte[]{5, (byte)command, 0, (byte)(raw.length == 4 ? 1 : 4)}); out.write(raw); out.writeShort(port);
        byte[] reply = new byte[10]; new DataInputStream(s.getInputStream()).readFully(reply);
        if (reply[1] != 0) return -reply[1]; return ((reply[8] & 255) << 8) | (reply[9] & 255);
    }
    @Test public void tcpNat64AndHalfCloseWork() throws Exception {
        try (ServerSocket echo = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); Socket client = authenticated()) {
            Future<?> f = server.submit(() -> {
                try (Socket s = echo.accept()) {
                    ByteArrayOutputStream all = new ByteArrayOutputStream(); int b;
                    while ((b = s.getInputStream().read()) != -1) all.write(b);
                    s.getOutputStream().write(all.toByteArray());
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            assertEquals(0, request(client, 1, prefix.synthesize(Nat64Prefix.numeric("127.0.0.1")), echo.getLocalPort()));
            byte[] data = new byte[32768]; Arrays.fill(data, (byte)42); client.getOutputStream().write(data); client.shutdownOutput();
            byte[] response = new byte[data.length]; new DataInputStream(client.getInputStream()).readFully(response);
            assertArrayEquals(data, response); assertEquals(-1, client.getInputStream().read()); f.get(4, TimeUnit.SECONDS);
        }
    }
    @Test public void rejectsDirectIpv4() throws Exception {
        try (Socket s = authenticated()) { assertEquals(-8, request(s, 1, Nat64Prefix.numeric("192.0.2.1"), 443)); }
    }
    @Test public void refusesUnauthenticatedClients() throws Exception {
        try (Socket s = new Socket("127.0.0.1", gateway.port())) {
            s.setSoTimeout(2000); s.getOutputStream().write(new byte[]{5, 1, 0});
            assertEquals(5, s.getInputStream().read()); assertEquals(255, s.getInputStream().read());
        }
    }
    @Test public void servesDnsOverTcp() throws Exception {
        try (Socket s = authenticated()) {
            assertEquals(0, request(s, 1, Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS), 53));
            Message q = Message.newQuery(Record.newRecord(Name.fromConstantString("ipv4only.arpa."), Type.AAAA, 1));
            DataOutputStream out = new DataOutputStream(s.getOutputStream()); byte[] bytes = q.toWire(); out.writeShort(bytes.length); out.write(bytes);
            DataInputStream in = new DataInputStream(s.getInputStream()); byte[] response = new byte[in.readUnsignedShort()]; in.readFully(response);
            assertEquals(2, new Message(response).getSection(Section.ANSWER).size());
        }
    }
    @Test public void udpNat64PreservesReturnAddress() throws Exception {
        try (DatagramSocket echo = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0)); Socket control = authenticated(); DatagramSocket client = new DatagramSocket()) {
            echo.setSoTimeout(4000); client.setSoTimeout(4000);
            Future<?> f = server.submit(() -> { try {
                DatagramPacket p = new DatagramPacket(new byte[4096], 4096); echo.receive(p); echo.send(p);
            } catch (IOException e) { throw new UncheckedIOException(e); } });
            int relay = request(control, 3, Nat64Prefix.numeric("0.0.0.0"), 0); assertTrue(relay > 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.write(new byte[]{0, 0, 0, 4}); out.write(prefix.synthesize(Nat64Prefix.numeric("127.0.0.1")).getAddress()); out.writeShort(echo.getLocalPort()); out.writeBytes("udp-data");
            byte[] request = bytes.toByteArray(); client.send(new DatagramPacket(request, request.length, new InetSocketAddress("127.0.0.1", relay)));
            DatagramPacket reply = new DatagramPacket(new byte[4096], 4096); client.receive(reply);
            assertArrayEquals(request, Arrays.copyOf(reply.getData(), reply.getLength())); f.get(4, TimeUnit.SECONDS);
            String sent = awaitLog("event=first-send");
            assertTrue(sent, sent.contains("txPackets=1 txBytes=8"));
            assertTrue(sent, sent.contains("remote=[127.0.0.1]:" + echo.getLocalPort()));
            String received = awaitLog("event=first-receive");
            assertTrue(received, received.contains("rxPackets=1 rxBytes=8 relayPackets=0 relayBytes=0"));
            String relayed = awaitLog("event=first-relay");
            assertTrue(relayed, relayed.contains("relayPackets=1 relayBytes=8"));
            control.close();
            String summary = awaitLog("event=close");
            assertTrue(summary, summary.contains("reason=association-close"));
            assertTrue(summary, summary.contains("txPackets=1 txBytes=8 rxPackets=1 rxBytes=8 relayPackets=1 relayBytes=8"));
        }
    }
    @Test public void udpNoReplyIsVisibleWhenGatewayStops() throws Exception {
        try (DatagramSocket sink = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             Socket control = authenticated(); DatagramSocket client = new DatagramSocket()) {
            sink.setSoTimeout(4000);
            int relay = request(control, 3, Nat64Prefix.numeric("0.0.0.0"), 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.write(new byte[]{0, 0, 0, 4}); out.write(prefix.synthesize(Nat64Prefix.numeric("127.0.0.1")).getAddress());
            out.writeShort(sink.getLocalPort()); out.write(new byte[1200]);
            byte[] request = bytes.toByteArray();
            for (int i = 0; i < 2; i++) {
                client.send(new DatagramPacket(request, request.length, new InetSocketAddress("127.0.0.1", relay)));
                sink.receive(new DatagramPacket(new byte[1500], 1500));
            }
            gateway.close();
            String summary = awaitLog("event=close");
            assertTrue(summary, summary.contains("reason=gateway-stop"));
            assertTrue(summary, summary.contains("txPackets=2 txBytes=2400 rxPackets=0 rxBytes=0 relayPackets=0 relayBytes=0"));
        }
    }
    @Test public void closingGatewayClosesActiveClients() throws Exception {
        try (Socket s = authenticated()) { gateway.close(); assertEquals(-1, s.getInputStream().read()); }
    }
}
