import Foundation
import Network

struct DNSWire {
    struct Question { let name: String; let type: Int; let cls: Int }
    struct RR { let name: String; let type: Int; let cls: Int; let ttl: Int; let data: [UInt8]; let alias: String? }
    let bytes: [UInt8]
    let id: Int
    let flags: Int
    let question: Question
    let answers: [RR]
    let records: [RR]
    let udpLimit: Int
    let dnssecOK: Bool
    var rcode: Int { flags & 15 }
    static func name(_ b: [UInt8], _ position: inout Int) throws -> String {
        var cursor = position, jumped = false, seen = Set<Int>(), labels = [String](), length = 1
        for _ in 0..<128 {
            guard cursor < b.count, seen.insert(cursor).inserted else { throw Failure.invalid }
            let size = Int(b[cursor]); cursor += 1
            if size == 0 { if !jumped { position = cursor }; return labels.joined(separator: ".").lowercased() }
            if size & 0xc0 == 0xc0 {
                guard cursor < b.count else { throw Failure.invalid }
                let next = (size & 63) << 8 | Int(b[cursor]); cursor += 1
                if !jumped { position = cursor; jumped = true }; cursor = next; continue
            }
            guard size <= 63, cursor + size <= b.count,
                  let label = String(bytes: b[cursor..<cursor+size], encoding: .ascii), !label.contains(".") else { throw Failure.invalid }
            length += size + 1; guard length <= 255 else { throw Failure.invalid }
            labels.append(label); cursor += size
        }
        throw Failure.invalid
    }
    enum Failure: Error { case invalid, timeout, busy }
    init(_ data: Data) throws {
        let b = [UInt8](data)
        guard b.count >= 12, PacketCodec.u16(b,4) == 1 else { throw Failure.invalid }
        var p = 12
        let name = try Self.name(b, &p)
        guard p+4 <= b.count else { throw Failure.invalid }
        question = Question(name: name, type: PacketCodec.u16(b,p), cls: PacketCodec.u16(b,p+2)); p += 4
        let counts = [PacketCodec.u16(b,6), PacketCodec.u16(b,8), PacketCodec.u16(b,10)]
        guard counts.reduce(0,+) <= 2048 else { throw Failure.invalid }
        var all = [RR](), answer = [RR](), limit = 512, dnssec = false
        for section in 0..<3 {
            for _ in 0..<counts[section] {
                let owner = try Self.name(b,&p)
                guard p+10 <= b.count else { throw Failure.invalid }
                let type = PacketCodec.u16(b,p), cls = PacketCodec.u16(b,p+2)
                let ttl = Int(b[p+4])<<24 | Int(b[p+5])<<16 | Int(b[p+6])<<8 | Int(b[p+7])
                let size = PacketCodec.u16(b,p+8); p += 10
                guard p+size <= b.count else { throw Failure.invalid }
                var alias: String?
                if type == 5 { var q = p; alias = try Self.name(b,&q); guard q == p+size else { throw Failure.invalid } }
                let rr = RR(name: owner,type: type,cls: cls,ttl: ttl,data: Array(b[p..<p+size]),alias: alias)
                all.append(rr); if section == 0 { answer.append(rr) }
                if section == 2 && type == 41 { limit = max(512,min(1232,cls)); dnssec = ttl & 0x8000 != 0 }
                p += size
            }
        }
        guard p == b.count else { throw Failure.invalid }
        bytes=b; id=PacketCodec.u16(b,0); flags=PacketCodec.u16(b,2); records=all; answers=answer; udpLimit=limit; dnssecOK=dnssec
    }
    static func encodeName(_ name: String) -> [UInt8] {
        name.split(separator: ".").flatMap { [UInt8($0.utf8.count)] + Array($0.utf8) } + [0]
    }
    var questionBytes: [UInt8] { Self.encodeName(question.name) + PacketCodec.word(question.type) + PacketCodec.word(question.cls) }
    func reply(rcode: Int, answers: [RR] = [], truncated: Bool = false) -> Data {
        let flags = 0x8080 | (self.flags & 0x0110) | rcode | (truncated ? 0x0200 : 0)
        var b = PacketCodec.word(id)
        b += PacketCodec.word(flags); b += [0,1]; b += PacketCodec.word(answers.count); b += [0,0,0,0]; b += questionBytes
        for rr in answers {
            let body = rr.alias.map(Self.encodeName) ?? rr.data
            b += Self.encodeName(rr.name); b += PacketCodec.word(rr.type); b += PacketCodec.word(rr.cls)
            b += PacketCodec.word(rr.ttl >> 16); b += PacketCodec.word(rr.ttl)
            b += PacketCodec.word(body.count) + body
        }
        return Data(b)
    }
    func queryA() -> Data {
        var b = PacketCodec.word(Int.random(in: 0...65535))
        b += PacketCodec.word(flags & 0x0110); b += [0,1,0,0,0,0,0,0]
        b += Self.encodeName(question.name); b += [0,1,0,1]
        return Data(b)
    }
    func validates(_ request: DNSWire) -> Bool {
        id == request.id && flags & 0x8000 != 0 && flags & 0x7800 == 0 && question.name == request.question.name
            && question.type == request.question.type && question.cls == request.question.cls
    }
    // One RR per log line, so large answers are not silently cut after 8 addresses.
    func logAnswers(_ emit: (String)->Void) {
        for (index, rr) in answers.enumerated() {
            var value=rr.alias.map { "alias=\($0)" } ?? "rdataBytes=\(rr.data.count)"
            if (rr.type==1 && rr.data.count==4) || (rr.type==28 && rr.data.count==16) {
                value="address=\(PacketCodec.address(rr.data))"
                if rr.type==28, rr.data.prefix(12)==PacketCodec.bytes(PacketCodec.prefix)!.prefix(12) {
                    value += " embeddedIPv4=\(PacketCodec.address(Array(rr.data.suffix(4))))"
                }
            }
            emit("rr=\(index+1)/\(answers.count) owner=\(rr.name) type=\(rr.type) ttl=\(rr.ttl) \(value)")
        }
    }
    func synthesize(for request: DNSWire) -> Data? {
        guard rcode == 0, flags & 0x0200 == 0 else { return nil }
        var current = question.name, seen = Set<String>(), chain = [RR](), ttl = 600
        for _ in 0..<16 {
            guard seen.insert(current).inserted else { return nil }
            let cnames = answers.filter { $0.name == current && $0.type == 5 && $0.cls == 1 }
            if let cname = cnames.first {
                guard cnames.count == 1, let alias = cname.alias else { return nil }
                ttl = min(ttl,cname.ttl); chain.append(RR(name:cname.name,type:5,cls:1,ttl:ttl,data:[],alias:alias)); current=alias
            } else {
                let values = answers.filter { $0.name == current && $0.type == 1 && $0.cls == 1 && $0.data.count == 4 }
                guard !values.isEmpty, let prefix = PacketCodec.bytes(PacketCodec.prefix) else { return nil }
                return request.reply(rcode:0, answers:chain + values.map {
                    RR(name:$0.name,type:28,cls:1,ttl:min(ttl,$0.ttl),data:Array(prefix.prefix(12))+$0.data,alias:nil)
                })
            }
        }
        return nil
    }
}

final class DNS64Server {
    let queue: DispatchQueue
    let host: String
    let port: UInt16
    let log: (String)->Void
    let overrides: DNSOverrides
    private var pending = [UUID:NWConnection]()
    private var requests = 0
    private var stopped = false
    init(queue: DispatchQueue, host: String, port: UInt16, overrides: DNSOverrides = try! DNSOverrides(), log: @escaping (String)->Void) {
        self.queue=queue; self.host=host; self.port=port; self.overrides=overrides; self.log=log
    }
    func stop() { stopped=true; pending.values.forEach { $0.cancel() }; pending.removeAll() }
    func answer(_ data: Data, tcp: Bool, completion: @escaping (Data)->Void) {
        guard !stopped else { return }
        guard let query = try? DNSWire(data) else {
            if data.count >= 2 { completion(Data(Array(data.prefix(2))+[0x80,1,0,0,0,0,0,0,0,0])) }; return
        }
        let trace = String(UUID().uuidString.prefix(8))
        var responseKind = "error"
        let finish: (Data)->Void = { response in
            guard !self.stopped else { return }
            self.requests -= 1
            let result = !tcp && response.count > query.udpLimit ? query.reply(rcode:0,truncated:true) : response
            if let reply=try? DNSWire(result) {
                self.log("DNS \(trace) downstream id=\(query.id) name=\(query.question.name) qtype=\(query.question.type) mode=\(responseKind) transport=\(tcp ? "TCP" : "UDP") rcode=\(reply.rcode) tc=\(reply.flags & 0x0200 != 0) answers=\(reply.answers.count) bytes=\(result.count) originalBytes=\(response.count) udpLimit=\(query.udpLimit)")
                reply.logAnswers { self.log("DNS \(trace) downstream \($0)") }
            }
            completion(result)
        }
        guard requests < 32 else { completion(query.reply(rcode:2)); return }; requests += 1
        log("DNS \(trace) client id=\(query.id) name=\(query.question.name) type=\(query.question.type) transport=\(tcp ? "TCP" : "UDP")")
        guard query.flags & 0xf800 == 0, query.question.cls == 1,
              ![251,252].contains(query.question.type), !query.records.contains(where: {$0.type==250}) else {
            finish(query.reply(rcode:4)); return
        }
        // Explicit local policy takes priority over native AAAA and DNSSEC flags.
        // Non-address types are NODATA, including HTTPS/SVCB address hints.
        if let ipv4=overrides.addresses[query.question.name], let address=PacketCodec.synthesize(ipv4) {
            let type=query.question.type
            let answers=[1,28].contains(type) ? [DNSWire.RR(name:query.question.name,type:type,cls:1,ttl:60,
                data:type==28 ? address : Array(address.suffix(4)),alias:nil)] : []
            responseKind="override"
            log("DNS \(trace) override name=\(query.question.name) type=\(type) ipv4=\(ipv4) ipv6=\(PacketCodec.address(address))")
            finish(query.reply(rcode:0,answers:answers)); return
        }
        if query.question.name == "ipv4only.arpa" {
            let ips = [[UInt8](arrayLiteral:192,0,0,170),[192,0,0,171]]
            let values = [1,28].contains(query.question.type) ? ips.map {
                DNSWire.RR(name:query.question.name,type:query.question.type,cls:1,ttl:60,
                    data:query.question.type == 28 ? Array(PacketCodec.bytes(PacketCodec.prefix)!.prefix(12))+$0 : $0,alias:nil)
            } : []
            responseKind="prefix-discovery"
            finish(query.reply(rcode:0,answers:values)); return
        }
        transport(data,trace:trace,purpose:"client") { primary in
            if self.stopped { return }
            let original = (try? primary.get()) ?? query.reply(rcode:2)
            let parsed = try? DNSWire(original)
            guard query.question.type == 28, !(query.flags & 0x10 != 0 && query.dnssecOK),
                  !(parsed?.rcode == 0 && ((parsed?.flags ?? 0) & 0x0200) == 0 && parsed?.answers.contains(where:{$0.type == 28}) == true) else {
                responseKind=query.question.type == 1 ? "A-passthrough" : "upstream-passthrough"
                finish(original); return
            }
            self.log("DNS \(trace) AAAA fallback to A")
            self.transport(query.queryA(),trace:trace,purpose:"dns64-A") { result in
                if let response = try? result.get(), let message = try? DNSWire(response), let synthetic = message.synthesize(for:query) {
                    responseKind="synthesized-AAAA"
                    if let reply=try? DNSWire(synthetic) {
                        reply.logAnswers { self.log("DNS \(trace) synthesized \($0)") }
                    }
                    finish(synthetic)
                } else { finish(original) }
            }
        }
    }
    private func transport(_ query: Data, trace: String, purpose: String, completion: @escaping (Result<Data,Error>)->Void) {
        exchange(query,tcp:false,trace:trace,purpose:purpose) { result in
            if let data = try? result.get(), let parsed = try? DNSWire(data), parsed.flags & 0x0200 != 0 {
                self.log("DNS \(trace) upstream purpose=\(purpose) truncated; retry TCP")
                self.exchange(query,tcp:true,trace:trace,purpose:purpose,completion:completion)
            } else { completion(result) }
        }
    }
    private func exchange(_ query: Data, tcp: Bool, trace: String, purpose: String, completion: @escaping (Result<Data,Error>)->Void) {
        guard !stopped, let request = try? DNSWire(query) else { completion(.failure(DNSWire.Failure.invalid)); return }
        let id=UUID(), connection=NWConnection(host:NWEndpoint.Host(host),port:NWEndpoint.Port(rawValue:port)!,using:tcp ? .tcp : .udp)
        log("DNS \(trace) upstream query purpose=\(purpose) id=\(request.id) \(host):\(port) transport=\(tcp ? "TCP" : "UDP") family=\(PacketCodec.synthesize(host) != nil ? "IPv4" : "IPv6") name=\(request.question.name) type=\(request.question.type)")
        pending[id]=connection
        let finish: (Result<Data,Error>)->Void = { result in
            guard self.pending.removeValue(forKey:id) != nil else { return }; connection.cancel()
            switch result {
            case .success(let data):
                guard let response = try? DNSWire(data), response.validates(request) else { completion(.failure(DNSWire.Failure.invalid)); return }
                self.log("DNS \(trace) upstream purpose=\(purpose) id=\(request.id) \(self.host):\(self.port) name=\(request.question.name) type=\(request.question.type) rcode=\(response.rcode) answers=\(response.answers.count)")
                response.logAnswers { self.log("DNS \(trace) upstream purpose=\(purpose) \($0)") }
                completion(.success(data))
            case .failure(let error): self.log("DNS \(trace) upstream purpose=\(purpose) id=\(request.id) failed: \(error)"); completion(result)
            }
        }
        connection.stateUpdateHandler = { if case .failed(let error) = $0 { finish(.failure(error)) } }
        connection.start(queue:queue)
        let content = tcp ? Data(PacketCodec.word(query.count))+query : query
        connection.send(content:content,completion:.contentProcessed { error in
            if let error { finish(.failure(error)); return }
            if tcp {
                connection.receive(minimumIncompleteLength:2,maximumLength:2) { data,_,_,error in
                    guard let data, data.count == 2, error == nil else { finish(.failure(error ?? DNSWire.Failure.invalid)); return }
                    let size=PacketCodec.u16([UInt8](data),0)
                    guard size>=12 else { finish(.failure(DNSWire.Failure.invalid)); return }
                    connection.receive(minimumIncompleteLength:size,maximumLength:size) { data,_,_,error in
                        if let data, data.count==size, error==nil { finish(.success(data)) }
                        else { finish(.failure(error ?? DNSWire.Failure.invalid)) }
                    }
                }
            } else {
                connection.receiveMessage { data,_,_,error in
                    if let data, error==nil { finish(.success(data)) } else { finish(.failure(error ?? DNSWire.Failure.invalid)) }
                }
            }
        })
        queue.asyncAfter(deadline:.now()+4) { finish(.failure(DNSWire.Failure.timeout)) }
    }
}
