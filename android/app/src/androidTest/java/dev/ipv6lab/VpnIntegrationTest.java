package dev.ipv6lab;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.ipv6lab.core.LocalGateway;
import dev.ipv6lab.core.Nat64Prefix;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.*;

/** Run only on an explicitly authorized test device. Requires VPN consent first. */
public final class VpnIntegrationTest {
    private Context context;
    private final Nat64Prefix prefix = new Nat64Prefix(Nat64Prefix.DEFAULT);
    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("Grant VPN consent before running integration tests", VpnService.prepare(context));
        new LabConfig(Nat64Prefix.DEFAULT, "", Collections.emptySet()).save(context);
        context.startForegroundService(new Intent(context, LabVpnService.class));
        for (int i = 0; i < 100 && !"运行中".equals(LabState.phase); i++) Thread.sleep(100);
        assertEquals(LabState.detail, "运行中", LabState.phase);
        Thread.sleep(500);
    }
    @After public void tearDown() throws Exception {
        context.startService(new Intent(context, LabVpnService.class).setAction(LabVpnService.STOP));
        for (int i = 0; i < 100 && LabState.active; i++) Thread.sleep(100);
        Thread.sleep(300);
    }
    @Test public void testDns64UdpThroughTun() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS), 53)); socket.setSoTimeout(5000);
            Message query = Message.newQuery(Record.newRecord(Name.fromConstantString("ipv4only.arpa."), Type.AAAA, 1));
            byte[] bytes = query.toWire(); socket.send(new DatagramPacket(bytes, bytes.length));
            DatagramPacket packet = new DatagramPacket(new byte[2048], 2048); socket.receive(packet);
            Message response = new Message(Arrays.copyOf(packet.getData(), packet.getLength()));
            assertEquals(query.getHeader().getID(), response.getHeader().getID());
            assertEquals(2, response.getSection(Section.ANSWER).size());
            assertEquals(prefix.synthesize(Nat64Prefix.numeric("192.0.0.170")), ((AAAARecord)response.getSection(Section.ANSWER).get(0)).getAddress());
        }
    }
    @Test public void testAndroidSystemResolverUsesDns64() throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName("ipv4only.arpa");
        boolean synthesized = false;
        for (InetAddress address : addresses) synthesized |= prefix.contains(address);
        assertTrue("System resolver did not return our synthesized AAAA: " + Arrays.toString(addresses)
                + "\n" + LabState.logs(), synthesized);
    }
    @Test public void testDns64TcpThroughTun() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS), 53), 5000); socket.setSoTimeout(5000);
            Message query = Message.newQuery(Record.newRecord(Name.fromConstantString("ipv4only.arpa."), Type.AAAA, 1));
            byte[] bytes = query.toWire(); DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(bytes.length); out.write(bytes);
            DataInputStream in = new DataInputStream(socket.getInputStream()); bytes = new byte[in.readUnsignedShort()]; in.readFully(bytes);
            assertEquals(2, new Message(bytes).getSection(Section.ANSWER).size());
        }
    }
    @Test public void testTcpNat64ThroughTun() throws Exception {
        // Host fixture is reachable through the emulator's physical IPv4 network.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(prefix.synthesize(Nat64Prefix.numeric("10.0.2.2")), 18080), 5000);
            socket.setSoTimeout(5000);
            byte[] data = new byte[32768]; Arrays.fill(data, (byte)0x5a); socket.getOutputStream().write(data); socket.shutdownOutput();
            byte[] actual = new byte[data.length];
            try { new DataInputStream(socket.getInputStream()).readFully(actual); }
            catch (IOException e) { throw new IOException(LabState.logs(), e); }
            assertTrue(Arrays.equals(data, actual));
        }
    }
    @Test public void testUdpNat64ThroughTun() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress(prefix.synthesize(Nat64Prefix.numeric("10.0.2.2")), 18081)); socket.setSoTimeout(5000);
            byte[] data = "ipv6-udp-test".getBytes("UTF-8"); socket.send(new DatagramPacket(data, data.length));
            DatagramPacket packet = new DatagramPacket(new byte[128], 128); socket.receive(packet);
            assertTrue(Arrays.equals(data, Arrays.copyOf(packet.getData(), packet.getLength())));
        }
    }
    @Test public void testDirectIpv4CannotReachSameHostFixture() throws Exception {
        try (Socket socket = new Socket()) {
            try { socket.connect(new InetSocketAddress("10.0.2.2", 18080), 2000); fail("Direct IPv4 leaked outside VPN"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
    }
    @Test public void testRepeatedStopStart() throws Exception {
        for (int iteration = 0; iteration < 3; iteration++) {
            context.startService(new Intent(context, LabVpnService.class).setAction(LabVpnService.STOP));
            for (int i = 0; i < 100 && LabState.active; i++) Thread.sleep(100);
            assertFalse(LabState.active); Thread.sleep(500);
            context.startForegroundService(new Intent(context, LabVpnService.class));
            for (int i = 0; i < 100 && !"运行中".equals(LabState.phase); i++) Thread.sleep(100);
            assertEquals(LabState.detail, "运行中", LabState.phase);
            testDns64UdpThroughTun();
        }
    }
}
