package dev.ipv6lab.core;

import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedFlags;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** DNS64 for a controlled test network; intentionally not a DNSSEC validating resolver. */
public final class Dns64 {
    public interface Exchange { byte[] query(byte[] request) throws IOException; }
    private final Nat64Prefix prefix;
    private final Exchange upstream;
    private final Telemetry telemetry;
    private final DnsOverrides overrides;
    private static final AtomicLong traces = new AtomicLong();

    public Dns64(Nat64Prefix prefix, Exchange upstream, Telemetry telemetry) {
        this(prefix, upstream, telemetry, new DnsOverrides(""));
    }
    public Dns64(Nat64Prefix prefix, Exchange upstream, Telemetry telemetry, DnsOverrides overrides) {
        this.overrides = overrides;
        this.prefix = prefix;
        this.upstream = upstream;
        this.telemetry = telemetry;
    }

    public byte[] answer(byte[] wire, boolean tcp) throws IOException {
        String trace = "DNS trace=" + traces.incrementAndGet();
        long started = System.nanoTime();
        Message query;
        try { query = new Message(wire); }
        catch (IOException e) {
            telemetry.error(trace + " malformed request: " + e);
            throw e;
        }
        telemetry.log(trace + " request transport=" + (tcp ? "TCP" : "UDP") + " " + questionSummary(query)
                + " rd=" + query.getHeader().getFlag(Flags.RD) + " cd=" + query.getHeader().getFlag(Flags.CD)
                + " do=" + (query.getOPT() != null && (query.getOPT().getFlags() & ExtendedFlags.DO) != 0));
        if (query.getHeader().getFlag(Flags.QR) || query.getHeader().getCount(Section.QUESTION) != 1)
            return finish(response(query, Rcode.FORMERR).toWire(), trace, started);
        if (query.getHeader().getOpcode() != Opcode.QUERY || query.getTSIG() != null)
            return finish(response(query, Rcode.NOTIMP).toWire(), trace, started);
        Record question = query.getQuestion();
        if (question.getDClass() != DClass.IN || question.getType() == Type.AXFR || question.getType() == Type.IXFR)
            return finish(response(query, Rcode.REFUSED).toWire(), trace, started);
        telemetry.dnsQueries.incrementAndGet();
        Message result;
        try { result = resolve(query, trace); }
        catch (IOException | IllegalArgumentException e) {
            telemetry.error(trace + " failed " + questionSummary(query) + " cause=" + e);
            result = response(query, Rcode.SERVFAIL);
        }
        int max = tcp ? 65535 : query.getOPT() == null ? 512 : Math.max(512, Math.min(1232, query.getOPT().getPayloadSize()));
        return finish(result.toWire(max), trace, started);
    }

    private Message resolve(Message query, String trace) throws IOException {
        Record question = query.getQuestion();
        java.net.InetAddress forced = overrides.lookup(question.getName().toString());
        if (forced != null) {
            Message result = response(query, Rcode.NOERROR);
            int type = question.getType();
            if (type == Type.AAAA) {
                result.addRecord(new AAAARecord(question.getName(), DClass.IN, 60, prefix.synthesize(forced)), Section.ANSWER);
                telemetry.synthesized.incrementAndGet();
            } else if (type == Type.A) {
                result.addRecord(new ARecord(question.getName(), DClass.IN, 60, forced), Section.ANSWER);
            }
            // Explicit local policy: no upstream aliases or HTTPS/SVCB address hints.
            telemetry.log(trace + " override name=" + question.getName() + " type=" + Type.string(type)
                    + " ipv4=" + forced.getHostAddress() + " ipv6=" + prefix.synthesize(forced).getHostAddress());
            return result;
        }
        // RFC 7050 discovery must advertise OUR prefix, never the upstream network's.
        if (question.getName().equals(Name.fromConstantString("ipv4only.arpa."))
                && (question.getType() == Type.AAAA || question.getType() == Type.A)) {
            Message result = response(query, Rcode.NOERROR);
            for (String ip : new String[]{"192.0.0.170", "192.0.0.171"}) {
                Record record = question.getType() == Type.A
                        ? new ARecord(question.getName(), DClass.IN, 60, Nat64Prefix.numeric(ip))
                        : new AAAARecord(question.getName(), DClass.IN, 60, prefix.synthesize(Nat64Prefix.numeric(ip)));
                result.addRecord(record, Section.ANSWER);
            }
            telemetry.log(trace + " local-prefix-discovery (no upstream query)");
            return result;
        }
        if (question.getType() != Type.AAAA) return exchange(query, trace);
        Message aaaa;
        try { aaaa = exchange(query, trace); }
        catch (IOException e) {
            telemetry.error(trace + " AAAA-query-failed; consider A fallback cause=" + e);
            aaaa = response(query, Rcode.SERVFAIL);
        }
        if (aaaa.getRcode() == Rcode.NOERROR && !aaaa.getHeader().getFlag(Flags.TC)) {
            for (Record r : aaaa.getSection(Section.ANSWER)) if (r.getType() == Type.AAAA) {
                telemetry.log(trace + " preserve-native-AAAA");
                return aaaa;
            }
        }

        // Do not forge signed answers for validating clients (DO + CD).
        if (query.getOPT() != null && (query.getOPT().getFlags() & ExtendedFlags.DO) != 0
                && query.getHeader().getFlag(Flags.CD)) {
            telemetry.log(trace + " skip-synthesis reason=DO+CD");
            return aaaa;
        }

        // Compatibility mode: even an AAAA error may coexist with usable A records.
        // Never synthesize from an erroneous, truncated or unvalidated A response.
        telemetry.log(trace + " query A for synthesis reason="
                + (aaaa.getRcode() != Rcode.NOERROR ? "AAAA-" + Rcode.string(aaaa.getRcode())
                : aaaa.getHeader().getFlag(Flags.TC) ? "AAAA-truncated" : "AAAA-empty"));
        Message aQuery = Message.newQuery(Record.newRecord(question.getName(), Type.A, question.getDClass()));
        if (!query.getHeader().getFlag(Flags.RD)) aQuery.getHeader().unsetFlag(Flags.RD);
        Message a = exchange(aQuery, trace);
        if (a.getRcode() != Rcode.NOERROR || a.getHeader().getFlag(Flags.TC)) {
            telemetry.log(trace + " skip-synthesis reason=A-error-or-truncated; return original AAAA response");
            return aaaa;
        }

        Message result = response(query, Rcode.NOERROR);
        // Only synthesize addresses reachable through the answer's CNAME chain.
        Name terminal = question.getName();
        Set<Name> seen = new HashSet<>();
        long ttl = 600;
        for (int i = 0; i < 16; i++) {
            if (!seen.add(terminal)) throw new IOException("CNAME loop");
            CNAMERecord cname = null;
            for (Record r : a.getSection(Section.ANSWER))
                if (r instanceof CNAMERecord && r.getName().equals(terminal)) { cname = (CNAMERecord) r; break; }
            if (cname == null) break;
            result.addRecord(cname, Section.ANSWER);
            ttl = Math.min(ttl, cname.getTTL());
            terminal = cname.getTarget();
        }
        int count = 0;
        for (Record r : a.getSection(Section.ANSWER)) {
            if (r instanceof ARecord && r.getName().equals(terminal)) {
                result.addRecord(new AAAARecord(r.getName(), r.getDClass(), Math.min(ttl, r.getTTL()),
                        prefix.synthesize(((ARecord) r).getAddress())), Section.ANSWER);
                count++;
            }
        }
        if (count == 0) {
            telemetry.log(trace + " skip-synthesis reason=no-A-for-CNAME-terminal terminal=" + terminal);
            return aaaa;
        }
        telemetry.synthesized.addAndGet(count);
        telemetry.log(trace + " DNS64 " + question.getName() + " · 合成 " + count + " 个 AAAA");
        return result; // Fresh response deliberately omits AD, RRSIG and negative SOA.
    }

    private Message exchange(Message query, String trace) throws IOException {
        long started = System.nanoTime();
        telemetry.log(trace + " upstream-query " + questionSummary(query));
        try {
            Message answer = new Message(upstream.query(query.toWire()));
            if (!answer.getHeader().getFlag(Flags.QR) || answer.getHeader().getID() != query.getHeader().getID()
                || answer.getHeader().getCount(Section.QUESTION) != 1
                || !query.getQuestion().equals(answer.getQuestion())) throw new IOException("DNS response mismatch");
            telemetry.log(trace + " upstream-response " + questionSummary(query) + " " + summary(answer)
                    + " elapsedMs=" + elapsed(started));
            return answer;
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException("upstream " + questionSummary(query) + " elapsedMs=" + elapsed(started) + " cause=" + e, e);
        }
    }

    private byte[] finish(byte[] wire, String trace, long started) throws IOException {
        // Inspect the serialized reply so TC and answer counts reflect UDP truncation.
        telemetry.log(trace + " response " + summary(new Message(wire)) + " elapsedMs=" + elapsed(started));
        return wire;
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static String questionSummary(Message message) {
        Record q = message.getQuestion();
        return "id=" + message.getHeader().getID() + (q == null ? " question=none"
                : " name=" + q.getName() + " type=" + Type.string(q.getType()) + " class=" + DClass.string(q.getDClass()));
    }

    private static String summary(Message message) {
        StringBuilder text = new StringBuilder("rcode=").append(Rcode.string(message.getRcode()))
                .append(" tc=").append(message.getHeader().getFlag(Flags.TC))
                .append(" answers=").append(message.getHeader().getCount(Section.ANSWER));
        int shown = 0;
        // Bounded metadata only: never dump arbitrary TXT records or packet contents.
        for (Record record : message.getSection(Section.ANSWER)) {
            if (!(record instanceof ARecord || record instanceof AAAARecord || record instanceof CNAMERecord)) continue;
            if (shown++ == 6) { text.append(" ..."); break; }
            text.append(" [").append(record.getName()).append(' ').append(Type.string(record.getType())).append(' ');
            if (record instanceof ARecord) text.append(((ARecord) record).getAddress().getHostAddress());
            else if (record instanceof AAAARecord) text.append(((AAAARecord) record).getAddress().getHostAddress());
            else text.append(((CNAMERecord) record).getTarget());
            text.append(']');
        }
        return text.toString();
    }

    private static Message response(Message query, int rcode) {
        Message result = new Message(query.getHeader().getID());
        result.getHeader().setFlag(Flags.QR);
        result.getHeader().setFlag(Flags.RA);
        if (query.getHeader().getFlag(Flags.RD)) result.getHeader().setFlag(Flags.RD);
        if (query.getHeader().getFlag(Flags.CD)) result.getHeader().setFlag(Flags.CD);
        result.getHeader().setRcode(rcode);
        if (query.getQuestion() != null) result.addRecord(query.getQuestion(), Section.QUESTION);
        if (query.getOPT() != null) result.addRecord(new OPTRecord(1232, 0, 0), Section.ADDITIONAL);
        return result;
    }
}
