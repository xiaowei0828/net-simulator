import Foundation
import Network
import NetworkExtension
import os

private final class TCPFlow {
    let id: UInt64
    var connection: NWConnection?
    var ready = false
    var dns = false
    var input = Data()
    var pending = [Data]()
    var pendingBytes = 0
    var dnsRequests = 0
    var receiving = false
    var eof = false
    var clientEOF = false
    var txBytes = 0, rxBytes = 0
    var receiveError: String?
    var last = ProcessInfo.processInfo.systemUptime
    init(_ id: UInt64) { self.id=id }
}
private final class UDPFlow {
    let connection: NWConnection
    let template: PacketCodec.Datagram
    let id: Int
    var last = ProcessInfo.processInfo.systemUptime
    var pendingBytes = 0
    var tx = 0, rx = 0
    init(_ connection: NWConnection, _ template: PacketCodec.Datagram, _ id: Int) {
        self.connection=connection; self.template=template; self.id=id
    }
}
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let queue = DispatchQueue(label:"dev.ipv6lab.gateway")
    private let logger = Logger(subsystem:"dev.ipv6lab.ios",category:"PacketTunnel")
    private var events = [String]()
    private var running = false
    private var full = true
    private var echoIPv4 = "192.0.2.1"
    private var dns: DNS64Server?
    private var dnsSource = "physical"
    private var physicalDNS = [String]()
    private var tcp = [UInt64:TCPFlow]()
    private var udp = [String:UDPFlow]()
    private var nextUDP = 0
    private var loggedIPv4 = Set<String>()
    private var timer: DispatchSourceTimer?
    private var packets = 0, dnsReplies = 0, udpReplies = 0, ipv4Dropped = 0, rejected = 0
    private var txBytes = 0, rxBytes = 0
    override func startTunnel(options:[String:NSObject]?,completionHandler:@escaping(Error?)->Void) {
        let config=(protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration ?? [:]
        echoIPv4=config["echoIPv4"] as? String ?? "192.0.2.1"
        full=config["fullTunnel"] as? Bool ?? true
        let overrides: DNSOverrides
        do { overrides=try DNSOverrides(config["dnsOverrides"] as? String ?? "") }
        catch { completionHandler(error); return }
        let configured=DNSUpstream.configuredHost(config)
        dnsSource=configured.isEmpty ? "physical" : "manual"
        physicalDNS=[]
        let resolver: String
        let resolverPort: UInt16
        if configured.isEmpty {
            // Snapshot before setTunnelNetworkSettings: afterwards the default
            // resolver is our virtual DNS, which would cause a forwarding loop.
            var buffer=[CChar](repeating:0,count:4096)
            let count=lab_system_dns(&buffer,buffer.count)
            let candidates=count > 0 ? String(cString:buffer).split(separator:"\n").map(String.init) : []
            physicalDNS=candidates
            guard let selected=DNSUpstream.select(candidates) else {
                let error=NSError(domain:"IPv6Lab",code:2,userInfo:[NSLocalizedDescriptionKey:"无法读取物理网络 DNS，请检查网络或手动指定上游 DNS"])
                logger.error("DNS selection failed candidates=\(candidates.joined(separator:","),privacy:.public)")
                completionHandler(error); return
            }
            resolver=selected; resolverPort=53
            logger.info("DNS selection source=physical candidates=\(candidates.joined(separator:","),privacy:.public) selected=\(selected,privacy:.public):53")
        } else {
            guard DNSUpstream.isUsable(configured),
                  let port=UInt16(exactly:config["resolverPort"] as? Int ?? 53), port > 0 else {
                completionHandler(NSError(domain:"IPv6Lab",code:3,userInfo:[NSLocalizedDescriptionKey:"上游 DNS 地址或端口无效"])); return
            }
            resolver=configured; resolverPort=port
        }
        let settings=NEPacketTunnelNetworkSettings(tunnelRemoteAddress:"192.0.2.1")
        settings.mtu=NSNumber(value:PacketCodec.mtu)
        let ipv6=NEIPv6Settings(addresses:["fd64:64:64:1::2"],networkPrefixLengths:[128])
        let localRoutes=[NEIPv6Route(destinationAddress:PacketCodec.prefix,networkPrefixLength:96),NEIPv6Route(destinationAddress:PacketCodec.dnsAddress,networkPrefixLength:128)]
        ipv6.includedRoutes=full ? [NEIPv6Route.default(),NEIPv6Route(destinationAddress:"2000::",networkPrefixLength:3),NEIPv6Route(destinationAddress:"fc00::",networkPrefixLength:7)]+localRoutes : localRoutes
        settings.ipv6Settings=ipv6
        // Only assign IPv6 to the TUN. Full-tunnel isolation is requested by
        // includeAllNetworks in the containing app, and verified on-device.
        settings.ipv4Settings=nil
        let servers=NEDNSSettings(servers:[PacketCodec.dnsAddress])
        servers.matchDomains=full ? [""] : ["ipv6lab.test"]
        servers.matchDomainsNoSearch=true; settings.dnsSettings=servers
        setTunnelNetworkSettings(settings) { error in
            self.queue.async {
                if let error { self.log("start failed: \(error)"); completionHandler(error); return }
                self.dns=DNS64Server(queue:self.queue,host:resolver,port:resolverPort,overrides:overrides,log:{ [weak self] in self?.log($0) })
                let context=Unmanaged.passUnretained(self).toOpaque()
                let result=lab_stack_start(context,{ ctx,bytes,count in
                    guard let ctx, let bytes else { return }
                    Unmanaged<PacketTunnelProvider>.fromOpaque(ctx).takeUnretainedValue().write(Data(bytes:bytes,count:count))
                },{ ctx,id,address,port in
                    guard let ctx, let address else { return }
                    Unmanaged<PacketTunnelProvider>.fromOpaque(ctx).takeUnretainedValue().accept(id,address:Array(UnsafeBufferPointer(start:address,count:16)),port:port)
                },{ ctx,id,event,bytes,count in
                    guard let ctx else { return }
                    let data=bytes.map { Data(bytes:$0,count:count) } ?? Data()
                    Unmanaged<PacketTunnelProvider>.fromOpaque(ctx).takeUnretainedValue().tcpEvent(id,event:event,data:data)
                })
                guard result == 0 else { self.dns?.stop(); completionHandler(NSError(domain:"IPv6Lab",code:1,userInfo:[NSLocalizedDescriptionKey:"TCP stack initialization failed"])); return }
                self.running=true
                let timer=DispatchSource.makeTimerSource(queue:self.queue)
                timer.schedule(deadline:.now()+0.25,repeating:0.25)
                timer.setEventHandler { [weak self] in self?.tick() }; self.timer=timer; timer.resume()
                self.log("started mtu=1500 dnsOverrides=\(overrides.addresses.count) tunnelIPv4=none fullTunnel=\(self.full) resolver=\(resolver):\(resolverPort) dnsSource=\(configured.isEmpty ? "physical" : "manual") family=\(PacketCodec.synthesize(resolver) != nil ? "IPv4" : "IPv6")")
                self.readPackets(); completionHandler(nil)
            }
        }
    }
    private func log(_ text:String) {
        logger.info("\(text, privacy:.public)"); events.append(text)
        if events.count>100 { events.removeFirst(events.count-100) }
    }
    private func write(_ data:Data) {
        guard running else { return }
        if !packetFlow.writePackets([data],withProtocols:[NSNumber(value:AF_INET6)]) { log("packetFlow write rejected") }
    }
    private func readPackets() {
        guard running else { return }
        packetFlow.readPackets { data,_ in self.queue.async {
            guard self.running else { return }
            data.forEach(self.handle); self.readPackets()
        } }
    }
    private func target(_ address:[UInt8],port:UInt16) -> String? {
        guard port>0, address.count==16, address[0] != 0xff,
              !(address[0]==0xfe && address[1]&0xc0==0x80), address.contains(where:{$0 != 0}),
              address != PacketCodec.bytes("::1"), address != PacketCodec.bytes("fd64:64:64:1::2") else { return nil }
        if address.prefix(12) == PacketCodec.bytes(PacketCodec.prefix)!.prefix(12) {
            let v4=Array(address.suffix(4))
            guard v4[0] != 0, v4[0] != 127, v4[0] < 224 else { return nil }
            return PacketCodec.address(v4)
        }
        // IPv4-mapped/compatible addresses must not become another direct IPv4 path.
        guard address.prefix(12).contains(where:{$0 != 0}), address.prefix(10).contains(where:{$0 != 0}) else { return nil }
        return PacketCodec.address(address)
    }
    private func handle(_ data:Data) {
        packets += 1
        guard let version=data.first else { return }
        if version>>4 == 4 {
            ipv4Dropped += 1
            let detail=PacketDiagnostics.ipv4(data)
            if loggedIPv4.count<128 && loggedIPv4.insert(detail).inserted {
                log("IPv4 dropped \(detail) bytes=\(data.count) total=\(ipv4Dropped)")
            } else if ipv4Dropped % 256 == 0 {
                log("IPv4 dropped total=\(ipv4Dropped) loggedFlows=\(loggedIPv4.count)")
            }
            return
        }
        guard version>>4==6, data.count>=40, data.count<=1500 else { rejected += 1; return }
        if data[data.startIndex+6]==6 { data.withUnsafeBytes { lab_stack_input($0.bindMemory(to:UInt8.self).baseAddress,data.count) }; return }
        guard let packet=PacketCodec.Datagram(data) else {
            rejected += 1
            if rejected<=16 { log("IPv6 rejected nextHeader=\(data[data.startIndex+6]) bytes=\(data.count) src=\(PacketCodec.address(Array(data.dropFirst(8).prefix(16)))) dst=\(PacketCodec.address(Array(data.dropFirst(24).prefix(16))))") }
            return
        }
        if packet.destination==PacketCodec.bytes(PacketCodec.dnsAddress),packet.destinationPort==53 {
            answerDNS(Data(packet.payload),tcp:false) { response in self.dnsReplies += 1; self.write(packet.reply([UInt8](response))) }; return
        }
        guard let host=target(packet.destination,port:UInt16(packet.destinationPort)) else { rejected += 1; return }
        let key="\(PacketCodec.address(packet.source)):\(packet.sourcePort)>\(PacketCodec.address(packet.destination)):\(packet.destinationPort)"
        var flow=udp[key]
        if flow==nil {
            guard udp.count<128 else { rejected += 1; return }
            nextUDP += 1
            let c=NWConnection(host:NWEndpoint.Host(host),port:NWEndpoint.Port(rawValue:UInt16(packet.destinationPort))!,using:.udp)
            let new=UDPFlow(c,packet,nextUDP); flow=new; udp[key]=new
            log("UDP flow=\(new.id) open \(key) -> \(host)")
            c.stateUpdateHandler = { [weak self,weak new] state in
                guard let self,let new,self.udp[key] === new else { return }
                if case .failed(let error)=state { self.closeUDP(key,reason:"failed \(error)") }
            }
            c.start(queue:queue); receiveUDP(key,new)
        }
        guard let flow, flow.pendingBytes+packet.payload.count<=65536 else { rejected += 1; return }
        flow.last=ProcessInfo.processInfo.systemUptime; flow.pendingBytes += packet.payload.count
        flow.connection.send(content:Data(packet.payload),completion:.contentProcessed { [weak self,weak flow] error in
            guard let self, let flow,self.udp[key] === flow else { return }
            flow.pendingBytes -= packet.payload.count
            if let error { self.closeUDP(key,reason:"send \(error)"); return }
            flow.tx += 1; self.txBytes += packet.payload.count
            if flow.tx==1 { self.log("UDP flow=\(flow.id) first-send bytes=\(packet.payload.count)") }
        })
    }
    private func receiveUDP(_ key:String,_ flow:UDPFlow) {
        flow.connection.receiveMessage { [weak self,weak flow] data,_,_,error in
            guard let self,let flow,self.running,self.udp[key] === flow else { return }
            if let error { self.closeUDP(key,reason:"receive \(error)"); return }
            if let data {
                if data.count<=PacketCodec.maxUDPPayload {
                    flow.rx += 1; flow.last=ProcessInfo.processInfo.systemUptime; self.rxBytes += data.count; self.udpReplies += 1
                    self.write(flow.template.reply([UInt8](data)))
                    if flow.rx==1 { self.log("UDP flow=\(flow.id) first-receive bytes=\(data.count)") }
                } else { self.rejected += 1; self.log("UDP flow=\(flow.id) oversized reply dropped bytes=\(data.count)") }
            }
            self.receiveUDP(key,flow)
        }
    }
    private func closeUDP(_ key:String,reason:String) {
        guard let flow=udp.removeValue(forKey:key) else { return }; flow.connection.cancel()
        log("UDP flow=\(flow.id) close reason=\(reason) txPackets=\(flow.tx) rxPackets=\(flow.rx)")
    }
    private func answerDNS(_ data:Data,tcp:Bool,completion:@escaping(Data)->Void) {
        // Keep deterministic fixtures for on-device checks; every other name goes upstream.
        if let q=try? DNSWire(data),q.question.name=="echo.ipv6lab.test",let answer=PacketCodec.dnsReply([UInt8](data),echoIPv4:echoIPv4) {
            completion(Data(answer)); return
        }
        dns?.answer(data,tcp:tcp,completion:completion)
    }
    private func accept(_ id:UInt64,address:[UInt8],port:UInt16) {
        let flow=TCPFlow(id); tcp[id]=flow
        if address==PacketCodec.bytes(PacketCodec.dnsAddress),port==53 { flow.dns=true; flow.ready=true; log("TCP flow=\(id) DNS"); return }
        guard let host=target(address,port:port) else { abort(id,"invalid destination"); return }
        log("TCP flow=\(id) \(PacketCodec.address(address)):\(port) -> \(host)")
        let c=NWConnection(host:NWEndpoint.Host(host),port:NWEndpoint.Port(rawValue:port)!,using:.tcp); flow.connection=c
        c.stateUpdateHandler = { [weak self,weak flow] state in
            guard let self,let flow,self.tcp[id] === flow else { return }
            switch state {
            case .ready: flow.ready=true; self.log("TCP flow=\(id) connected"); self.receiveTCP(flow)
            case .failed(let error):
                if !flow.ready { self.abort(id,"connect \(error)") }
                else {
                    // A state change can precede delivery of buffered bytes / EOF.
                    // Let receive report the stream's terminal result before destroying it.
                    self.log("TCP flow=\(id) transport state failed \(error); draining receive")
                    self.receiveTCP(flow)
                }
            default: break
            }
        }
        c.start(queue:queue)
        queue.asyncAfter(deadline:.now()+8) { [weak self,weak flow] in
            if let self,let flow,self.tcp[id] === flow,!flow.ready { self.abort(id,"connect timeout") }
        }
    }
    private func tcpEvent(_ id:UInt64,event:Int32,data:Data) {
        guard let flow=tcp[id] else { return }; flow.last=ProcessInfo.processInfo.systemUptime
        switch event {
        case 1:
            if flow.dns {
                flow.input.append(data); lab_tcp_ack(id,data.count)
                guard flow.input.count<=65537 else { abort(id,"DNS input limit"); return }
                parseTCPDNS(flow)
            } else {
                flow.connection?.send(content:data,completion:.contentProcessed { [weak self,weak flow] error in
                    guard let self,let flow,self.tcp[id] === flow else { return }
                    if let error { self.abort(id,"send \(error)") } else { self.txBytes += data.count; flow.txBytes += data.count; lab_tcp_ack(id,data.count) }
                })
            }
        case 2:
            if flow.dns { flow.clientEOF=true; lab_tcp_client_finished(id); if flow.dnsRequests==0 { flow.eof=true; flushTCP(flow) } }
            else { flow.connection?.send(content:nil,contentContext:.finalMessage,isComplete:true,completion:.contentProcessed { [weak self,weak flow] error in
                guard let self,let flow,self.tcp[id] === flow else { return }
                if let error { self.abort(id,"FIN \(error)") } else { lab_tcp_client_finished(id) }
            }) }
        case 3: flushTCP(flow)
        case 4: tcp.removeValue(forKey:id)?.connection?.cancel(); log("TCP flow=\(id) closed txBytes=\(flow.txBytes) rxBytes=\(flow.rxBytes)")
        default: break
        }
    }
    private func parseTCPDNS(_ flow:TCPFlow) {
        while flow.input.count>=2 {
            let size=PacketCodec.u16([UInt8](flow.input.prefix(2)),0)
            guard size>=12 else { abort(flow.id,"short DNS"); return }
            guard flow.input.count>=size+2 else { return }
            guard flow.dnsRequests<4 else { abort(flow.id,"DNS pipeline limit"); return }
            let message=Data(flow.input.dropFirst(2).prefix(size)); flow.input.removeFirst(size+2); flow.dnsRequests += 1
            answerDNS(message,tcp:true) { [weak self,weak flow] response in
                guard let self,let flow,self.tcp[flow.id] === flow else { return }
                flow.dnsRequests -= 1
                if flow.clientEOF && flow.dnsRequests==0 { flow.eof=true }
                self.enqueueTCP(flow,Data(PacketCodec.word(response.count))+response)
            }
        }
    }
    private func enqueueTCP(_ flow:TCPFlow,_ data:Data) {
        guard flow.pendingBytes+data.count<=131072 else { abort(flow.id,"download buffer limit"); return }
        // Keep each lwIP write below its send window, including DNS-over-TCP messages.
        for start in stride(from:0,to:data.count,by:8192) { flow.pending.append(data.subdata(in:start..<min(start+8192,data.count))) }
        flow.pendingBytes += data.count; flushTCP(flow)
    }
    private func flushTCP(_ flow:TCPFlow) {
        guard tcp[flow.id] === flow else { return }
        while let data=flow.pending.first {
            let result=data.withUnsafeBytes { lab_tcp_write(flow.id,$0.bindMemory(to:UInt8.self).baseAddress,data.count) }
            if result==0 { return }
            if result<0 { abort(flow.id,"stack write"); return }
            flow.pending.removeFirst(); flow.pendingBytes -= data.count
        }
        if let error=flow.receiveError { abort(flow.id,error) }
        else if flow.eof { lab_tcp_eof(flow.id) }
        else if !flow.dns { receiveTCP(flow) }
    }
    private func receiveTCP(_ flow:TCPFlow) {
        guard !flow.receiving,!flow.eof,flow.receiveError==nil,flow.ready,flow.pending.isEmpty else { return }
        flow.receiving=true
        flow.connection?.receive(minimumIncompleteLength:1,maximumLength:8192) { [weak self,weak flow] data,_,complete,error in
            guard let self,let flow,self.tcp[flow.id] === flow else { return }
            flow.receiving=false; flow.last=ProcessInfo.processInfo.systemUptime
            flow.eof=complete
            flow.receiveError=error.map { "receive \($0)" }
            if let data,!data.isEmpty { self.rxBytes += data.count; flow.rxBytes += data.count; self.enqueueTCP(flow,data) }
            else { self.flushTCP(flow) }
        }
    }
    private func abort(_ id:UInt64,_ reason:String) {
        log("TCP flow=\(id) abort \(reason)")
        // Never abort synchronously inside lwIP's accept/receive callbacks.
        queue.async { if self.tcp[id] != nil { lab_tcp_abort(id) } }
    }
    private func tick() {
        guard running else { return }; lab_stack_tick()
        let now=ProcessInfo.processInfo.systemUptime
        for (key,flow) in udp where now-flow.last>30 { closeUDP(key,reason:"idle") }
        for (id,flow) in tcp { if now-flow.last>120 { abort(id,"idle") } else if flow.eof { flushTCP(flow) } }
    }
    override func handleAppMessage(_ data:Data,completionHandler:((Data?)->Void)?) {
        queue.async {
            let report:[String:Any] = ["running":self.running,"fullTunnel":self.full,"tunnelIPv4Configured":false,"mtu":1500,"packets":self.packets,
                "dnsOverrides":self.dns?.overrides.addresses ?? [:],"dnsSource":self.dnsSource,"physicalDNS":self.physicalDNS,"resolver":self.dns?.host ?? "","resolverPort":Int(self.dns?.port ?? 0),
                "dnsReplies":self.dnsReplies,"udpReplies":self.udpReplies,"ipv4Dropped":self.ipv4Dropped,"rejected":self.rejected,
                "tcpFlows":self.tcp.count,"udpFlows":self.udp.count,"txBytes":self.txBytes,"rxBytes":self.rxBytes,"events":self.events]
            completionHandler?(try? JSONSerialization.data(withJSONObject:report,options:[.prettyPrinted,.sortedKeys]))
        }
    }
    override func stopTunnel(with reason:NEProviderStopReason,completionHandler:@escaping()->Void) {
        queue.async {
            self.running=false; self.timer?.cancel(); self.timer=nil; self.dns?.stop(); self.dns=nil
            for key in Array(self.udp.keys) { self.closeUDP(key,reason:"stop") }
            lab_stack_stop(); self.tcp.values.forEach { $0.connection?.cancel() }; self.tcp.removeAll()
            self.log("stopped reason=\(reason.rawValue)"); completionHandler()
        }
    }
}
