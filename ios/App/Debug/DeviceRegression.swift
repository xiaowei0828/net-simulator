#if DEBUG
import Foundation
import NetworkExtension

extension VPNModel {
    func startDeviceRegressionIfRequested() {
        if ProcessInfo.processInfo.arguments.contains("--probe-overrides") {
            Task { await self.runOverrideRegression() }; return
        }
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--probe-echo"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1) {
            let target = ProcessInfo.processInfo.arguments[index + 1]
            if let ri = ProcessInfo.processInfo.arguments.firstIndex(of: "--resolver"), ProcessInfo.processInfo.arguments.indices.contains(ri+1) {
                let parts = ProcessInfo.processInfo.arguments[ri+1].split(separator:":")
                self.resolver = String(parts[0]); self.resolverPort = parts.count>1 ? Int(parts[1]) ?? 53 : 53
            }
            if ProcessInfo.processInfo.arguments.contains("--system-dns") { self.resolver=""; self.resolverPort=53 }
            Task { await self.runDeviceRegression(target: target) }
        }
    }


    // Explicit developer launch argument only; ordinary launches remain interactive.
    func runDeviceRegression(target: String) async {
        guard PacketCodec.synthesize(target) != nil else { return }
        let publicCheck = ProcessInfo.processInfo.arguments.contains("--public-check")
        let runID = UUID().uuidString
        var steps = [[String: Any]]()
        func save(_ finished: Bool) {
            let document: [String: Any] = ["runID": runID, "timestamp": ISO8601DateFormatter().string(from: Date()),
                "echoIPv4": target, "resolver": resolver, "resolverPort": resolverPort, "publicCheck": publicCheck, "mtu": PacketCodec.mtu, "finished": finished, "vpnStatus": status, "steps": steps]
            do {
                let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("device-probe.json")
                try JSONSerialization.data(withJSONObject: document, options: [.prettyPrinted, .sortedKeys]).write(to: url, options: .atomic)
            } catch { print("Probe report write failed: \(error)") }
        }
        func record(_ name: String, _ result: String) {
            steps.append(["name": name, "result": result]); save(false)
        }
        save(false)
        if active {
            stop()
            guard await waitFor(seconds:10,condition:{ self.manager?.connection.status == .disconnected }) else {
                record("stop-before-baseline","FAIL：VPN 未断开"); save(true); return
            }
        }
        echoIPv4=target; fullTunnel=true
        probeIPv4(requireVPN:false)
        _ = await waitFor(seconds:8,condition:{ !self.busy })
        record("ipv4-positive-control",report)
        probeIPv4(requireVPN:false,tcp:true)
        _ = await waitFor(seconds:8,condition:{ !self.busy })
        record("ipv4-tcp-positive-control",report)
        probeIPv4(requireVPN:false,scoped:true)
        _ = await waitFor(seconds:8,condition:{ !self.busy })
        record("ipv4-scoped-positive-control",report)
        if publicCheck {
            probeHTTPS(requireVPN:false)
            _ = await waitFor(seconds:16,condition:{ !self.busy })
            record("https-dualstack-positive-control",report)
        }
        for round in 1...2 {
            if active {
                stop()
                guard await waitFor(seconds: 10, condition: { self.manager?.connection.status == .disconnected }) else {
                    record("stop-before-round", "FAIL：VPN 未断开"); save(true); return
                }
            }
            echoIPv4 = target
            start()
            guard await waitFor(seconds: 180, condition: { self.manager?.connection.status == .connected && !self.busy }) else {
                record("round-\(round)-connect", "FAIL：VPN 连接未完成：\(report)"); stop(); save(true); return
            }
            record("round-\(round)-connect", "PASS：Packet Tunnel connected")
            let checks = ["dns-fixture", "dns-tcp", "udp-44", "udp-1452", "udp-session", "system-dns-udp", "tcp-32k"] + (publicCheck ? ["https-ipv4", "https-dualstack"] : ["upstream-dns-tcp"]) + ["ipv4-blocked", "ipv4-scoped-blocked", "ipv4-tcp-blocked"]
            for name in checks {
                report = "Running \(name)"
                if name == "dns-fixture" { probeDNS() }
                else if name == "dns-tcp" { probeDNS(overTCP:true) }
                else if name == "udp-session" { probeUDPSession() }
                else if name == "https-ipv4" { probeHTTPS(ipv4Only:true) }
                else if name == "https-dualstack" { probeHTTPS() }
                else if name == "ipv4-tcp-blocked" { probeIPv4(tcp:true) }
                else if name == "tcp-32k" { probeTCP() }
                else if name == "upstream-dns-tcp" { probeTCP(useUpstreamName:true) }
                else if name == "ipv4-blocked" { probeIPv4() }
                else if name == "ipv4-scoped-blocked" { probeIPv4(scoped:true) }
                else { probeUDP(payloadSize: name == "udp-1452" ? PacketCodec.maxUDPPayload : 44, useSystemDNS: name == "system-dns-udp") }
                let completed = await waitFor(seconds: 16, condition: { !self.busy })
                record("round-\(round)-\(name)", completed ? report : "FAIL：检查超时")
            }
            report = "Waiting for diagnostics"
            snapshot()
            _ = await waitFor(seconds: 5, condition: { self.report != "Waiting for diagnostics" })
            record("round-\(round)-diagnostics", report)
            stop()
            let disconnected = await waitFor(seconds: 10, condition: { self.manager?.connection.status == .disconnected })
            record("round-\(round)-stop", disconnected ? "PASS：VPN disconnected" : "FAIL：VPN 未断开")
            if !disconnected { save(true); return }
        }
        report = steps.allSatisfy { !($0["result"] as? String ?? "").hasPrefix("FAIL") }
            ? "真机探针已完成两轮；结果已保存到 device-probe.json" : "真机探针存在失败；结果已保存到 device-probe.json"
        save(true)
        // Leave the installed tool on a normal DNS port after the local fixture run.
        if resolverPort != 53, let proto=manager?.protocolConfiguration as? NETunnelProviderProtocol {
            resolver=""; resolverPort=53
            proto.providerConfiguration?["resolver"] = resolver
            proto.providerConfiguration?["resolverPort"] = resolverPort
            proto.providerConfiguration?["resolverSelectionVersion"] = 1
            manager?.saveToPreferences { error in if let error { print("Restore DNS settings failed: \(error)") } }
        }
    }
    func runOverrideRegression() async {
        let name="pull-f5-sg01.tiktokcdn.com", ip="170.187.221.24", runID=UUID().uuidString
        var steps=[[String:String]]()
        func save(_ finished: Bool) {
            let data:[String:Any]=["runID":runID,"timestamp":ISO8601DateFormatter().string(from:Date()),"finished":finished,"vpnStatus":status,"steps":steps]
            let url=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("override-probe.json")
            do { try JSONSerialization.data(withJSONObject:data,options:[.prettyPrinted,.sortedKeys]).write(to:url,options:.atomic) }
            catch { print("Override report write failed: \(error)") }
        }
        func record(_ name:String,_ result:String) { steps.append(["name":name,"result":result]); save(false) }
        save(false)
        if manager?.connection.status != .disconnected && manager?.connection.status != .invalid && manager != nil {
            stop()
            guard await waitFor(seconds:10,condition:{ self.manager?.connection.status == .disconnected }) else {
                record("stop-before-test","FAIL：VPN 未断开"); save(true); return
            }
        }
        do {
            var entries=try DNSOverrides(dnsOverrides).addresses
            entries[name]=ip
            dnsOverrides=entries.keys.sorted().map { "\($0):\(entries[$0]!)" }.joined(separator:"\n")
        } catch { record("config","FAIL：\(error)"); save(true); return }
        start()
        guard await waitFor(seconds:30,condition:{ self.manager?.connection.status == .connected && !self.busy }) else {
            record("connect","FAIL：\(report)"); stop(); save(true); return
        }
        record("connect","PASS：Packet Tunnel connected")
        for tcp in [false,true] {
            for type in [1,28,65] {
                report="Running override query"
                probeOverrideDNS(name:name,ipv4:ip,type:type,tcp:tcp)
                let completed=await waitFor(seconds:8,condition:{ !self.busy })
                record("\(tcp ? "TCP" : "UDP")-\(type)",completed ? report : "FAIL：probe timeout")
            }
        }
        report="Waiting for diagnostics"; snapshot()
        _ = await waitFor(seconds:5,condition:{ self.report != "Waiting for diagnostics" })
        record("diagnostics",report)
        stop()
        let disconnected=await waitFor(seconds:10,condition:{ self.manager?.connection.status == .disconnected })
        record("stop",disconnected ? "PASS：VPN disconnected" : "FAIL：VPN 未断开")
        report="域名映射真机检查完成：结果已保存到 override-probe.json"
        save(true)
    }
    private func waitFor(seconds: Int, condition: () -> Bool) async -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + Double(seconds)
        while !condition() && ProcessInfo.processInfo.systemUptime < deadline {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        return condition()
    }
}
#endif
