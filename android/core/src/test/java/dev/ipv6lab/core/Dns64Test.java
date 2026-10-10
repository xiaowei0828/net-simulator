package dev.ipv6lab.core;

import org.junit.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class Dns64Test {
    private final Nat64Prefix prefix = new Nat64Prefix(Nat64Prefix.DEFAULT);
    private final Name name = Name.fromConstantString("example.test.");
    private Message query(int type) { return Message.newQuery(Record.newRecord(name, type, DClass.IN)); }
    private Message response(Message q, int rcode) {
        Message m = new Message(q.getHeader().getID()); m.getHeader().setFlag(Flags.QR); m.getHeader().setRcode(rcode);
        m.addRecord(q.getQuestion(), Section.QUESTION); return m;
    }
    private Dns64 resolver(Dns64.Exchange e) { return new Dns64(prefix, e, new Telemetry(s -> {})); }

    @Test public void synthesizesWhenAaaaIsNoData() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dns64 dns = resolver(w -> {
            calls.incrementAndGet(); Message q = new Message(w); Message r = response(q, 0);
            if (q.getQuestion().getType() == Type.A) r.addRecord(new ARecord(name, 1, 300, Nat64Prefix.numeric("192.0.2.1")), Section.ANSWER);
            return r.toWire();
        });
        Message q = query(Type.AAAA), r = new Message(dns.answer(q.toWire(), false));
        assertEquals(q.getHeader().getID(), r.getHeader().getID()); assertEquals(2, calls.get());
        AAAARecord rr = (AAAARecord) r.getSection(Section.ANSWER).get(0);
        assertEquals(prefix.synthesize(Nat64Prefix.numeric("192.0.2.1")), rr.getAddress()); assertEquals(300, rr.getTTL());
        assertFalse(r.getHeader().getFlag(Flags.AD)); assertEquals(q.getQuestion(), r.getQuestion());
    }
    @Test public void preservesNativeAaaaWithoutAQuery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dns64 dns = resolver(w -> {
            calls.incrementAndGet(); Message q = new Message(w), r = response(q, 0);
            r.addRecord(new AAAARecord(name, 1, 200, Nat64Prefix.numeric("2001:db8::1")), Section.ANSWER); return r.toWire();
        });
        Message result = new Message(dns.answer(query(Type.AAAA).toWire(), false));
        assertEquals(1, calls.get()); assertEquals(Nat64Prefix.numeric("2001:db8::1"), ((AAAARecord)result.getSection(Section.ANSWER).get(0)).getAddress());
    }
    @Test public void preservesNxdomainWhenAFallbackAlsoHasNoAddress() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dns64 dns = resolver(w -> { calls.incrementAndGet(); return response(new Message(w), Rcode.NXDOMAIN).toWire(); });
        assertEquals(Rcode.NXDOMAIN, new Message(dns.answer(query(Type.AAAA).toWire(), false)).getRcode()); assertEquals(2, calls.get());
    }
    @Test public void aaaaErrorsStillSynthesizeFromValidAWithCname() throws Exception {
        for (int rcode : new int[]{Rcode.REFUSED, Rcode.SERVFAIL, Rcode.NXDOMAIN, Rcode.FORMERR, Rcode.NOTIMP}) {
            AtomicInteger calls = new AtomicInteger();
            Name alias = Name.fromConstantString("target.test.");
            Dns64 dns = resolver(w -> {
                calls.incrementAndGet(); Message q = new Message(w);
                if (q.getQuestion().getType() == Type.AAAA) return response(q, rcode).toWire();
                Message r = response(q, Rcode.NOERROR);
                r.addRecord(new CNAMERecord(name, 1, 45, alias), Section.ANSWER);
                r.addRecord(new ARecord(alias, 1, 90, Nat64Prefix.numeric("46.151.192.193")), Section.ANSWER);
                return r.toWire();
            });
            Message q = query(Type.AAAA), result = new Message(dns.answer(q.toWire(), false));
            assertEquals(2, calls.get()); assertEquals(Rcode.NOERROR, result.getRcode());
            assertEquals(q.getHeader().getID(), result.getHeader().getID()); assertEquals(q.getQuestion(), result.getQuestion());
            assertEquals(2, result.getSection(Section.ANSWER).size());
            AAAARecord address = (AAAARecord) result.getSection(Section.ANSWER).get(1);
            assertEquals(alias, address.getName()); assertEquals(45, address.getTTL());
            assertEquals(prefix.synthesize(Nat64Prefix.numeric("46.151.192.193")), address.getAddress());
            assertFalse(result.getHeader().getFlag(Flags.AD));
        }
    }
    @Test public void aaaaTransportAndInvalidRepliesCanRecoverViaA() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            final int failure = mode;
            AtomicInteger calls = new AtomicInteger();
            Dns64 dns = resolver(w -> {
                calls.incrementAndGet(); Message q = new Message(w), r = response(q, 0);
                if (q.getQuestion().getType() == Type.AAAA) {
                    if (failure == 0) throw new java.net.SocketTimeoutException("timeout");
                    if (failure == 1) return new byte[]{0};
                    if (failure == 2) r.getHeader().setID((q.getHeader().getID() + 1) & 65535);
                    if (failure == 3) r.getHeader().setFlag(Flags.TC);
                } else r.addRecord(new ARecord(name, 1, 60, Nat64Prefix.numeric("192.0.2.1")), Section.ANSWER);
                return r.toWire();
            });
            Message result = new Message(dns.answer(query(Type.AAAA).toWire(), false));
            assertEquals(2, calls.get()); assertEquals(Rcode.NOERROR, result.getRcode());
            assertFalse(result.getHeader().getFlag(Flags.TC));
            assertEquals(prefix.synthesize(Nat64Prefix.numeric("192.0.2.1")),
                    ((AAAARecord) result.getSection(Section.ANSWER).get(0)).getAddress());
        }
    }
    @Test public void invalidAFallbackNeverProducesSyntheticSuccess() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            final int failure = mode;
            Dns64 dns = resolver(w -> {
                Message q = new Message(w);
                if (q.getQuestion().getType() == Type.AAAA) return response(q, Rcode.REFUSED).toWire();
                Message r = response(q, failure == 0 ? Rcode.SERVFAIL : Rcode.NOERROR);
                r.addRecord(new ARecord(failure == 5 ? Name.fromConstantString("unrelated.test.") : name,
                        1, 60, Nat64Prefix.numeric("192.0.2.1")), Section.ANSWER);
                if (failure == 1) r.getHeader().setFlag(Flags.TC);
                if (failure == 2) throw new IOException("A timeout");
                if (failure == 3) r.getHeader().setID((q.getHeader().getID() + 1) & 65535);
                if (failure == 4) return response(q, 0).toWire();
                return r.toWire();
            });
            Message result = new Message(dns.answer(query(Type.AAAA).toWire(), false));
            assertEquals(failure == 2 || failure == 3 ? Rcode.SERVFAIL : Rcode.REFUSED, result.getRcode());
            assertEquals(0, result.getSection(Section.ANSWER).size());
        }
    }
    @Test public void validatingClientStillDoesNotSynthesizeAfterError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dns64 dns = resolver(w -> { calls.incrementAndGet(); return response(new Message(w), Rcode.REFUSED).toWire(); });
        Message q = query(Type.AAAA); q.getHeader().setFlag(Flags.CD);
        q.addRecord(new OPTRecord(1232, 0, 0, ExtendedFlags.DO), Section.ADDITIONAL);
        assertEquals(Rcode.REFUSED, new Message(dns.answer(q.toWire(), false)).getRcode());
        assertEquals(1, calls.get());
    }
    @Test public void upstreamFailureIsServfailNotSyntheticSuccess() throws Exception {
        Dns64 dns = resolver(w -> { throw new IOException("timeout"); });
        assertEquals(Rcode.SERVFAIL, new Message(dns.answer(query(Type.AAAA).toWire(), false)).getRcode());
    }
    @Test public void preservesAQueries() throws Exception {
        Dns64 dns = resolver(w -> {
            Message q = new Message(w), r = response(q, 0); r.addRecord(new ARecord(name, 1, 60, Nat64Prefix.numeric("192.0.2.1")), Section.ANSWER); return r.toWire();
        });
        assertTrue(new Message(dns.answer(query(Type.A).toWire(), false)).getSection(Section.ANSWER).get(0) instanceof ARecord);
    }
    @Test public void cnameIsReencodedAndUnrelatedAddressesIgnored() throws Exception {
        Name alias = Name.fromConstantString("target.test.");
        Dns64 dns = resolver(w -> {
            Message q = new Message(w), r = response(q, 0);
            r.addRecord(new CNAMERecord(name, 1, 45, alias), Section.ANSWER);
            if (q.getQuestion().getType() == Type.A) {
                r.addRecord(new ARecord(alias, 1, 200, Nat64Prefix.numeric("192.0.2.9")), Section.ANSWER);
                r.addRecord(new ARecord(Name.fromConstantString("unrelated.test."), 1, 60, Nat64Prefix.numeric("192.0.2.8")), Section.ANSWER);
            }
            return r.toWire();
        });
        Message r = new Message(dns.answer(query(Type.AAAA).toWire(), false));
        assertEquals(2, r.getSection(Section.ANSWER).size()); assertTrue(r.getSection(Section.ANSWER).get(0) instanceof CNAMERecord);
        assertEquals(alias, r.getSection(Section.ANSWER).get(1).getName()); assertEquals(45, r.getSection(Section.ANSWER).get(1).getTTL());
    }
    @Test public void discoveryUsesOurPrefixWithoutUpstream() throws Exception {
        Dns64 dns = resolver(w -> { throw new AssertionError("Discovery must be local"); });
        Message q = Message.newQuery(Record.newRecord(Name.fromConstantString("ipv4only.arpa."), Type.AAAA, 1));
        Message r = new Message(dns.answer(q.toWire(), false));
        assertEquals(2, r.getSection(Section.ANSWER).size());
        assertEquals(prefix.synthesize(Nat64Prefix.numeric("192.0.0.170")), ((AAAARecord)r.getSection(Section.ANSWER).get(0)).getAddress());
    }
    @Test public void noSynthesisForValidatingClient() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Dns64 dns = resolver(w -> { calls.incrementAndGet(); return response(new Message(w), 0).toWire(); });
        Message q = query(Type.AAAA); q.getHeader().setFlag(Flags.CD); q.addRecord(new OPTRecord(1232, 0, 0, ExtendedFlags.DO), Section.ADDITIONAL);
        assertEquals(0, new Message(dns.answer(q.toWire(), false)).getSection(Section.ANSWER).size()); assertEquals(1, calls.get());
    }
    @Test public void responseIdMismatchIsRejected() throws Exception {
        Dns64 dns = resolver(w -> { Message q = new Message(w), r = response(q, 0); r.getHeader().setID((q.getHeader().getID() + 1) & 65535); return r.toWire(); });
        assertEquals(Rcode.SERVFAIL, new Message(dns.answer(query(Type.AAAA).toWire(), false)).getRcode());
    }
    @Test public void oversizedUdpResponseTruncatesButTcpPreserves() throws Exception {
        Dns64 dns = resolver(w -> {
            Message q = new Message(w), r = response(q, 0);
            if (q.getQuestion().getType() == Type.A) for (int i = 1; i < 90; i++)
                r.addRecord(new ARecord(name, 1, 60, Nat64Prefix.numeric("192.0.2." + i)), Section.ANSWER);
            return r.toWire();
        });
        byte[] q = query(Type.AAAA).toWire(), udp = dns.answer(q, false);
        assertTrue(udp.length <= 512); assertTrue(new Message(udp).getHeader().getFlag(Flags.TC));
        assertEquals(89, new Message(dns.answer(q, true)).getSection(Section.ANSWER).size());
    }
    @Test public void emptyQuestionReturnsFormerr() throws Exception {
        assertEquals(Rcode.FORMERR, new Message(resolver(w -> w).answer(new Message(7).toWire(), false)).getRcode());
    }
}
