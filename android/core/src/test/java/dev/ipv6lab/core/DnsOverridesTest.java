package dev.ipv6lab.core;

import org.junit.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DnsOverridesTest {
    private static final String HOST = "pull-f5-sg01.tiktokcdn.com.";
    private final DnsOverrides overrides = new DnsOverrides("Pull-F5-SG01.TikTokCDN.com.：170.187.221.24\nother.test:192.0.2.1");
    @Test public void normalizesAndMatchesExactly() {
        assertEquals("170.187.221.24", overrides.lookup(HOST.toUpperCase(java.util.Locale.ROOT)).getHostAddress());
        assertNull(overrides.lookup("sub." + HOST));
        assertNull(overrides.lookup("tiktokcdn.com"));
        assertEquals(overrides.text, new DnsOverrides(overrides.text).text);
        assertEquals(0, new DnsOverrides("\n  \r\n").size());
    }
    @Test public void rejectsInvalidOrAmbiguousRules() {
        for (String invalid : new String[]{"https://a.test:1.2.3.4", "*.test:1.2.3.4", "a..test:1.2.3.4",
                "a.test:256.1.2.3", "a.test:01.2.3.4", "a.test:127.0.0.1", "a.test:0.1.2.3", "a.test:224.1.2.3",
                "a.test:2001:db8::1", "a.test:", "a.test:1.2.3.4\nA.TEST.:1.2.3.5", "ipv4only.arpa:1.2.3.4",
                "echo.ipv6lab.test:1.2.3.4", "170.187.221.24:1.2.3.4"}) {
            try { new DnsOverrides(invalid); fail(invalid); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().startsWith("域名映射第 ")); }
        }
    }
    @Test public void answersWithoutUpstreamOnUdpAndTcpIncludingDnssec() throws Exception {
        Nat64Prefix prefix = new Nat64Prefix("2001:db8:64::/96");
        Dns64 dns = new Dns64(prefix, w -> { throw new AssertionError("Override must not query upstream"); },
                new Telemetry(s -> {}), overrides);
        for (boolean tcp : new boolean[]{false, true}) {
            for (int type : new int[]{Type.A, Type.AAAA, Type.HTTPS, Type.SVCB, Type.TXT}) {
                Message query = Message.newQuery(Record.newRecord(Name.fromConstantString(HOST), type, DClass.IN));
                query.getHeader().setFlag(Flags.CD);
                query.addRecord(new OPTRecord(1232, 0, 0, ExtendedFlags.DO), Section.ADDITIONAL);
                Message reply = new Message(dns.answer(query.toWire(), tcp));
                assertEquals(query.getHeader().getID(), reply.getHeader().getID());
                assertEquals(query.getQuestion(), reply.getQuestion());
                assertEquals(Rcode.NOERROR, reply.getRcode());
                assertFalse(reply.getHeader().getFlag(Flags.AD));
                if (type == Type.A || type == Type.AAAA) {
                    assertEquals(1, reply.getSection(Section.ANSWER).size());
                    Record rr = reply.getSection(Section.ANSWER).get(0);
                    assertEquals(60, rr.getTTL());
                    if (type == Type.A) assertEquals(Nat64Prefix.numeric("170.187.221.24"), ((ARecord)rr).getAddress());
                    else assertEquals(prefix.synthesize(Nat64Prefix.numeric("170.187.221.24")), ((AAAARecord)rr).getAddress());
                } else assertTrue(reply.getSection(Section.ANSWER).isEmpty());
            }
        }
    }
    @Test public void nonmatchingNamesStillUseUpstream() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dns64 dns = new Dns64(new Nat64Prefix(Nat64Prefix.DEFAULT), wire -> {
            calls.incrementAndGet(); Message response = new Message(wire); response.getHeader().setFlag(Flags.QR);
            return response.toWire();
        }, new Telemetry(s -> {}), overrides);
        dns.answer(Message.newQuery(Record.newRecord(Name.fromConstantString("sub." + HOST), Type.A, DClass.IN)).toWire(), false);
        assertEquals(1, calls.get());
    }
}
