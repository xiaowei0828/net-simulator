package dev.ipv6lab;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ipv6lab.core.LocalGateway;
import dev.ipv6lab.core.Nat64Prefix;
import org.junit.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Explicitly selected real-device test; keeps the sample rule and stops VPN afterwards. */
public final class DnsOverrideDeviceTest {
    @Test public void mappedAnswersThroughVpn() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull("VPN consent required", VpnService.prepare(context));
        String host="pull-f5-sg01.tiktokcdn.com", ip="170.187.221.24";
        LabConfig old=LabConfig.load(context);
        StringBuilder rules=new StringBuilder();
        for (String row:old.dnsOverrides.text.split("\n"))
            if (!row.isEmpty() && !row.startsWith(host+":")) rules.append(row).append('\n');
        rules.append(host).append(':').append(ip);
        new LabConfig(old.prefix,old.resolver,old.packages,rules.toString()).save(context);
        assertEquals(ip,LabConfig.load(context).dnsOverrides.lookup(host).getHostAddress());
        Nat64Prefix prefix=new Nat64Prefix(old.prefix);
        try {
            context.startForegroundService(new Intent(context,LabVpnService.class));
            for (int i=0;i<150 && !"运行中".equals(LabState.phase);i++) Thread.sleep(100);
            assertEquals(LabState.detail,"运行中",LabState.phase);
            for (boolean tcp:new boolean[]{false,true}) {
                for (int type:new int[]{Type.A,Type.AAAA,Type.HTTPS}) {
                    Message q=Message.newQuery(Record.newRecord(Name.fromString(host+"."),type,DClass.IN));
                    byte[] bytes=q.toWire(), reply;
                    if (tcp) {
                        try (Socket socket=new Socket()) {
                            socket.connect(new InetSocketAddress(Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS),53),5000);
                            socket.setSoTimeout(5000);
                            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                            out.writeShort(bytes.length);out.write(bytes);out.flush();
                            DataInputStream in=new DataInputStream(socket.getInputStream());
                            reply=new byte[in.readUnsignedShort()];in.readFully(reply);
                        }
                    } else {
                        try (DatagramSocket socket=new DatagramSocket()) {
                            socket.connect(new InetSocketAddress(Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS),53));socket.setSoTimeout(5000);
                            socket.send(new DatagramPacket(bytes,bytes.length));
                            DatagramPacket packet=new DatagramPacket(new byte[2048],2048);socket.receive(packet);
                            reply=Arrays.copyOf(packet.getData(),packet.getLength());
                        }
                    }
                    Message response=new Message(reply);
                    assertEquals(q.getHeader().getID(),response.getHeader().getID());
                    assertEquals(q.getQuestion(),response.getQuestion());
                    assertEquals(Rcode.NOERROR,response.getRcode());
                    if (type==Type.HTTPS) assertTrue(response.getSection(Section.ANSWER).isEmpty());
                    else {
                        assertEquals(1,response.getSection(Section.ANSWER).size());
                        Record rr=response.getSection(Section.ANSWER).get(0);
                        assertEquals(60,rr.getTTL());
                        if (type==Type.A) assertEquals(Nat64Prefix.numeric(ip),((ARecord)rr).getAddress());
                        else assertEquals(prefix.synthesize(Nat64Prefix.numeric(ip)),((AAAARecord)rr).getAddress());
                    }
                    LabState.log("DEVICE CHECK PASS override "+(tcp?"TCP":"UDP")+" type="+Type.string(type)+" ip="+ip);
                }
            }
            for (String line:LabState.logs().split("\n"))
                assertFalse("Unexpected upstream query for override",line.contains("upstream-query") && line.contains(host));
        } finally {
            context.startService(new Intent(context,LabVpnService.class).setAction(LabVpnService.STOP));
            for (int i=0;i<100 && LabState.active;i++) Thread.sleep(100);
            assertFalse("VPN must be stopped after test",LabState.active);
        }
    }
}
