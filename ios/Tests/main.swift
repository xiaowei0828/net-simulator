import Foundation

var checks = 0
func check(_ condition: @autoclosure () -> Bool, _ name: String) {
    precondition(condition(), name); checks += 1; print("PASS \(name)")
}
let client = PacketCodec.bytes("fd64:64:64:1::2")!
let server = PacketCodec.bytes(PacketCodec.dnsAddress)!
let query = PacketCodec.query("echo.ipv6lab.test")
let request = PacketCodec.udp(source: client, destination: server, sourcePort: 43000, destinationPort: 53, payload: query)
let parsed = PacketCodec.Datagram(request)!
check(parsed.payload == query && parsed.sourcePort == 43000 && parsed.destinationPort == 53, "IPv6 UDP parse/checksum")
let answer = PacketCodec.dnsReply(parsed.payload, echoIPv4: "192.0.2.9")!
check(PacketCodec.u16(answer, 6) == 1 && answer.suffix(16) == PacketCodec.synthesize("192.0.2.9")!, "DNS synthetic AAAA")
let reply = PacketCodec.Datagram(parsed.reply(answer))!
check(reply.source == server && reply.destination == client && reply.destinationPort == 43000 && reply.payload == answer, "reply addresses/ports/checksum")
var corrupted = [UInt8](request); corrupted[48] ^= 1
check(PacketCodec.Datagram(Data(corrupted)) == nil, "corrupt checksum rejected")
check(PacketCodec.Datagram(Data([UInt8](request).dropLast())) == nil, "truncated packet rejected")
var fragmented = [UInt8](request); fragmented[6] = 44
check(PacketCodec.Datagram(Data(fragmented)) == nil, "fragment header not misparsed as UDP")
check(PacketCodec.dnsReply([0,1,2], echoIPv4: "192.0.2.9") == nil, "short DNS rejected")
check(PacketCodec.dnsReply(PacketCodec.query("missing.test"), echoIPv4: "192.0.2.9")![3] == 3, "unknown fixture NXDOMAIN")
check(PacketCodec.u16(PacketCodec.dnsReply(PacketCodec.query("ipv4only.arpa"), echoIPv4: "192.0.2.9")!, 6) == 2, "local prefix discovery")
check(PacketCodec.synthesize("999.0.0.1") == nil, "invalid IPv4 rejected")
check(PacketCodec.address(PacketCodec.synthesize("43.175.138.242")!) == "fd64:64:64::2baf:8af2", "NAT64 byte order")


let dnsQuery = try! DNSWire(Data(PacketCodec.query("source.test")))
let aQuery = try! DNSWire(dnsQuery.queryA())
let records = [DNSWire.RR(name:"source.test",type:5,cls:1,ttl:40,data:[],alias:"target.test"),
               DNSWire.RR(name:"target.test",type:1,cls:1,ttl:100,data:[192,0,2,7],alias:nil),
               DNSWire.RR(name:"unrelated.test",type:1,cls:1,ttl:100,data:[192,0,2,8],alias:nil)]
let aResponse = try! DNSWire(aQuery.reply(rcode:0,answers:records))
let synthesized = try! DNSWire(aResponse.synthesize(for:dnsQuery)!)
check(synthesized.id==dnsQuery.id && synthesized.answers.count==2 && synthesized.answers.last!.data==PacketCodec.synthesize("192.0.2.7")!, "CNAME DNS64 only synthesizes reachable A records")
check(synthesized.answers.last!.ttl==40 && synthesized.flags & 0x20 == 0, "DNS64 TTL bounded by alias and AD cleared")
check(aResponse.validates(aQuery) && !aResponse.validates(dnsQuery), "DNS response question and ID validated")
let refused = try! DNSWire(aQuery.reply(rcode:5))
check(refused.synthesize(for:dnsQuery)==nil, "A error cannot produce synthetic success")
let cycle = try! DNSWire(aQuery.reply(rcode:0,answers:[DNSWire.RR(name:"source.test",type:5,cls:1,ttl:40,data:[],alias:"source.test")]))
check(cycle.synthesize(for:dnsQuery)==nil, "CNAME cycle rejected")
var pointerLoop = [UInt8](dnsQuery.bytes.prefix(12)); pointerLoop += [0xc0,0x0c,0,28,0,1]
check((try? DNSWire(Data(pointerLoop)))==nil, "DNS compression cycle rejected")
var truncatedDNS = aResponse.bytes; truncatedDNS.removeLast()
check((try? DNSWire(Data(truncatedDNS)))==nil, "DNS truncated RDATA rejected")
check(DNSUpstream.configuredHost([:]).isEmpty && DNSUpstream.configuredHost(["resolver":"1.1.1.1"]).isEmpty, "new and legacy default use physical DNS")
check(DNSUpstream.configuredHost(["resolver":"1.1.1.1","resolverSelectionVersion":1]) == "1.1.1.1", "explicit public DNS override preserved")
check(DNSUpstream.configuredHost(["resolver":"10.1.5.1"]) == "10.1.5.1", "legacy custom resolver preserved")
check(DNSUpstream.select(["2001:db8::53","10.1.5.1","10.1.5.2"]) == "10.1.5.1", "physical IPv4 DNS preferred in original order")
check(DNSUpstream.select(["2001:db8::53"]) == "2001:db8::53", "IPv6-only physical DNS supported")
check(DNSUpstream.select([PacketCodec.dnsAddress,"fd64:64:64::101:101","127.0.0.1","::1","0.0.0.0","::","invalid",""]) == nil, "unusable and tunnel DNS rejected without public fallback")
check(DNSUpstream.select(["fe80::1","fe80::1%en0"]) == "fe80::1%en0", "link-local DNS requires and preserves interface scope")
let manyA=(1...12).map { DNSWire.RR(name:"source.test",type:1,cls:1,ttl:60,data:[192,0,2,UInt8($0)],alias:nil) }
let manyResponse=try! DNSWire(aQuery.reply(rcode:0,answers:manyA))
var addressLogs=[String]()
manyResponse.logAnswers { addressLogs.append($0) }
check(addressLogs.count==12 && addressLogs.last!.contains("address=192.0.2.12"), "DNS logs retain addresses beyond first eight")
let manySynthetic=try! DNSWire(manyResponse.synthesize(for:dnsQuery)!)
addressLogs=[]; manySynthetic.logAnswers { addressLogs.append($0) }
check(manySynthetic.question.type==28 && manySynthetic.answers.allSatisfy { $0.type==28 } && addressLogs.last!.contains("embeddedIPv4=192.0.2.12"), "DNS64 AAAA answer logs complete IPv4 mapping")
check(manyResponse.question.type==1 && manyResponse.answers.allSatisfy { $0.type==1 }, "A response remains an A answer")
var v4=[UInt8](repeating:0,count:32)
v4[0]=0x46; v4[3]=32; v4[9]=17; v4.replaceSubrange(12..<20,with:[192,0,2,2,170,187,221,12]); v4[26]=1; v4[27]=187
check(PacketDiagnostics.ipv4(Data(v4)).contains("dst=170.187.221.12") && PacketDiagnostics.ipv4(Data(v4)).contains("dstPort=443"), "IPv4 diagnostic respects variable header length")
v4[7]=1
check(!PacketDiagnostics.ipv4(Data(v4)).contains("dstPort"), "IPv4 noninitial fragment has no false ports")
check(PacketDiagnostics.ipv4(Data(v4.prefix(16)))=="invalid-header", "IPv4 truncated diagnostic is safe")
let overrides=try! DNSOverrides(" Pull-F5-SG01.TikTokCDN.com.：170.187.221.24\nother.test:192.0.2.1 ")
check(overrides.addresses["pull-f5-sg01.tiktokcdn.com"]=="170.187.221.24" && overrides.addresses["sub.pull-f5-sg01.tiktokcdn.com"]==nil, "override normalization and exact matching")
check((try! DNSOverrides(overrides.text)).text==overrides.text && (try! DNSOverrides("\n  \r\n")).addresses.isEmpty, "override settings round trip and empty defaults")
let invalidOverrides=["https://a.test:1.2.3.4", "*.test:1.2.3.4", "a..test:1.2.3.4", "a.test:256.1.2.3", "a.test:01.2.3.4", "a.test:127.0.0.1", "a.test:0.1.2.3", "a.test:224.1.2.3", "a.test:2001:db8::1", "a.test:", "a.test:1.2.3.4\nA.TEST.:1.2.3.5", "ipv4only.arpa:1.2.3.4", "echo.ipv6lab.test:1.2.3.4", "170.187.221.24:1.2.3.4"]
check(invalidOverrides.allSatisfy { (try? DNSOverrides($0))==nil }, "invalid ambiguous and reserved mappings rejected")
let forcedServer=DNS64Server(queue:DispatchQueue(label:"override.test"),host:"192.0.2.53",port:53,overrides:overrides,log:{ _ in })
for tcp in [false,true] {
    for type in [1,28,64,65,16] {
        var wire=PacketCodec.query("pull-f5-sg01.tiktokcdn.com")
        wire.replaceSubrange((wire.count-4)..<(wire.count-2),with:PacketCodec.word(type))
        // DO+CD requests still honor explicit local overrides, without claiming AD.
        wire[3] |= 0x10; wire[11]=1; wire += [0,0,41,4,208,0,0,0x80,0,0,0]
        let original=try! DNSWire(Data(wire))
        var received: DNSWire?
        forcedServer.answer(Data(wire),tcp:tcp) { received=try! DNSWire($0) }
        check(received != nil, "override answered locally without upstream type=\(type) tcp=\(tcp)")
        let reply=received!
        check(reply.validates(original) && reply.rcode==0 && reply.flags & 0x20==0, "override preserves question and ID and clears AD")
        if type==1 || type==28 {
            let expected=type==28 ? PacketCodec.synthesize("170.187.221.24")! : [170,187,221,24]
            check(reply.answers.count==1 && reply.answers[0].type==type && reply.answers[0].data==expected && reply.answers[0].ttl==60, "override returns selected address in requested family")
        } else { check(reply.answers.isEmpty, "override suppresses unrelated records and HTTPS/SVCB hints") }
    }
}
forcedServer.stop()
print("\(checks) checks passed")
