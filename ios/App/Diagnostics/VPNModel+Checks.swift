import Foundation
import Network
import NetworkExtension

// Accessed exclusively on VPNModel’s serial client queue.
private final class ClientExchangeState: @unchecked Sendable { var done=false }

extension VPNModel {
#if DEBUG
    func probeOverrideDNS(name: String, ipv4: String, type: Int, tcp: Bool) {
        guard let address=PacketCodec.synthesize(ipv4) else { return }
        var query=PacketCodec.query(name)
        query.replaceSubrange((query.count-4)..<(query.count-2),with:PacketCodec.word(type))
        let hasAddress=type==1 || type==28
        var expected=Array(query.prefix(2))+[0x81,0x80,0,1,0,UInt8(hasAddress ? 1 : 0),0,0,0,0]+Array(query.dropFirst(12))
        if hasAddress {
            let body=type==28 ? address : Array(address.suffix(4))
            expected += Array(query.dropFirst(12).dropLast(4))+PacketCodec.word(type)+[0,1,0,0,0,60]+PacketCodec.word(body.count)+body
        }
        let expectedData=tcp ? Data(PacketCodec.word(expected.count)+expected) : Data(expected)
        exchange(host:PacketCodec.dnsAddress,port:53,payload:tcp ? Data(PacketCodec.word(query.count)+query) : Data(query),tcp:tcp) {
            $0==expectedData ? "PASS：override \(name) type=\(type) transport=\(tcp ? "TCP" : "UDP") ipv4=\(ipv4) ipv6=\(PacketCodec.address(address))" : "FAIL：override response mismatch type=\(type) bytes=\($0.count)"
        }
    }
#endif
    func probeDNS(overTCP: Bool = false) {
        let query = PacketCodec.query("echo.ipv6lab.test")
        let expected = PacketCodec.dnsReply(query, echoIPv4: echoIPv4)
        exchange(host: PacketCodec.dnsAddress, port: 53, payload: overTCP ? Data(PacketCodec.word(query.count)+query) : Data(query), tcp: overTCP) { data in
            data == expected.map { overTCP ? Data(PacketCodec.word($0.count)+$0) : Data($0) } ? "PASS：TUN 收到 DNS 查询，返回匹配目标的合成 AAAA" : "FAIL：DNS 响应不符合预期"
        }
    }
    func probeUDP(payloadSize: Int = 44, useSystemDNS: Bool = false) {
        guard let address = PacketCodec.synthesize(echoIPv4) else { return }
        var payload = Data(("IPv6Lab-" + UUID().uuidString).utf8)
        if payloadSize > payload.count { payload.append(Data(repeating: 0x61, count: payloadSize - payload.count)) }
        exchange(host: useSystemDNS ? "echo.ipv6lab.test" : PacketCodec.address(address), port: 18081, payload: payload) { data in
            data == payload ? "PASS：合成 IPv6 → 本地 UDP NAT64 → IPv4 回显 → IPv6 返回，数据一致" : "FAIL：UDP 回显数据不一致"
        }
    }
    func probeUDPSession() {
        guard !busy, manager?.connection.status == .connected, let address=PacketCodec.synthesize(echoIPv4) else { return }
        busy=true
        let c=NWConnection(host:NWEndpoint.Host(PacketCodec.address(address)),port:18081,using:.udp)
        queue.async {
            let state=ClientExchangeState()
            let finish: (String)->Void = { message in
                guard !state.done else { return }; state.done=true; c.cancel()
                Task { @MainActor in self.busy=false; self.report=message }
            }
            @Sendable func send(_ index:Int) {
                guard !state.done else { return }
                let payload=Data(("IPv6Lab-\(index)-"+UUID().uuidString).utf8)
                c.send(content:payload,completion:.contentProcessed { error in
                    if let error { finish("FAIL：UDP session \(error)"); return }
                    c.receiveMessage { data,_,_,error in
                        guard error==nil,data==payload else { finish("FAIL：UDP session reply mismatch"); return }
                        if index==5 { finish("PASS：同一 UDP 会话连续 5 次回显一致") }
                        else { self.queue.asyncAfter(deadline:.now()+0.1) { send(index+1) } }
                    }
                })
            }
            c.start(queue:self.queue); send(1)
            self.queue.asyncAfter(deadline:.now()+5) { finish("FAIL：UDP session timeout") }
        }
    }
    func probeTCP(useUpstreamName: Bool = false) {
        guard let address = PacketCodec.synthesize(echoIPv4) else { return }
        var payload = Data(("IPv6Lab-" + UUID().uuidString).utf8)
        payload.append(Data(repeating: 0x5a, count: 32768-payload.count))
        exchange(host: useUpstreamName ? "upstream.ipv6lab.test" : PacketCodec.address(address), port: 18080, payload: payload, tcp: true) {
            $0 == payload ? "PASS：TCP 32 KiB 回显和半关闭完整" : "FAIL：TCP 回显不一致"
        }
    }
    func probeIPv4(requireVPN: Bool = true, scoped: Bool = false, tcp: Bool = false) {
        guard !busy, !requireVPN || manager?.connection.status == .connected else { return }
        busy=true
        let host=echoIPv4
        DispatchQueue.global(qos:.userInitiated).async {
            let result=IPv4SocketProbe.run(host:host,tcp:tcp,scoped:scoped)
            let label="AF_INET \(tcp ? "TCP" : "UDP") scoped=\(scoped) \(host) \(result.detail)"
            let message: String
            if result.detail.hasPrefix("setup-failed") || result.detail.hasPrefix("socket errno") {
                message="FAIL：探针未能执行 \(label)"
            } else if requireVPN {
                message=result.received ? "FAIL：直接 IPv4 可通信 \(label)" : "PASS：直接 IPv4 未能通信（需结合基线） \(label)"
            } else {
                message=result.received && result.detail=="echo matched" ? "PASS：直接 IPv4 基线可达 \(label)" : "FAIL：IPv4 基线不可达 \(label)"
            }
            Task { @MainActor in self.busy=false; self.report=message }
        }
    }

    func probeHTTPS(ipv4Only: Bool = false, requireVPN: Bool = true) {
        guard !busy, !requireVPN || manager?.connection.status == .connected else { return }; busy=true
        let session=URLSession(configuration:.ephemeral)
        let host = ipv4Only ? "ipv4.icanhazip.com" : "example.com"
        var request=URLRequest(url:URL(string:"https://\(host)/")!); request.timeoutInterval=12
        session.dataTask(with:request) { data,response,error in
            session.finishTasksAndInvalidate()
            Task { @MainActor in
                self.busy=false
                if let response=response as? HTTPURLResponse, response.statusCode==200, let data,!data.isEmpty {
                    self.report="PASS：HTTPS \(host) HTTP 200，\(data.count) 字节"
                } else { self.report="FAIL：HTTPS \(String(describing:error))" }
            }
        }.resume()
    }
    private func exchange(host: String, port: UInt16, payload: Data, tcp: Bool = false, requireVPN: Bool = true, expectBlocked: Bool = false, scoped: Bool = false, result: @escaping (Data) -> String) {
        guard (!requireVPN || manager?.connection.status == .connected), !busy else { return }
        busy = true
        let parameters: NWParameters = tcp ? .tcp : .udp
        if scoped { parameters.requiredInterfaceType = .wifi }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: parameters)
        queue.async {
            var done = false
            let finish: (String) -> Void = { message in
                guard !done else { return }; done = true; connection.cancel()
                let text = expectBlocked ? (message.hasPrefix("FAIL") ? "PASS：直接 IPv4 未能通信（需结合基线）" : "FAIL：直接 IPv4 绕过阻断") : message
                Task { @MainActor in self.busy = false; self.report = text }
            }
            connection.stateUpdateHandler = { state in
                if case .failed(let error) = state { finish("FAIL：\(error)") }
            }
            connection.start(queue: self.queue)
            connection.send(content: payload, contentContext: tcp ? .finalMessage : .defaultMessage, isComplete: true, completion: .contentProcessed { error in
                if let error { finish("FAIL：\(error)"); return }
                let received: @Sendable (Data?, NWConnection.ContentContext?, Bool, NWError?) -> Void = { data, _, _, error in
                    if let data, error == nil { finish(result(data)) }
                    else { finish("FAIL：\(String(describing: error))") }
                }
                if tcp { connection.receive(minimumIncompleteLength:payload.count,maximumLength:max(payload.count,4096),completion:received) }
                else { connection.receiveMessage(completion:received) }
            })
            self.queue.asyncAfter(deadline: .now() + 5) { finish("FAIL：5 秒未收到响应") }
        }
    }
}
