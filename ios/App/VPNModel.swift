import Foundation
import Combine
import NetworkExtension

@MainActor final class VPNModel: ObservableObject {
    @Published var status = "尚未安装 VPN 配置"
    @Published var report = "本地 IPv6 测试环境：无隧道 IPv4 地址、DNS64 及 TCP/UDP 转发。系统保留流量和显式绕行仍需验证。"
    @Published var echoIPv4 = "192.0.2.1"
    @Published var resolver = ""
    @Published var dnsOverrides = ""
    @Published var fullTunnel = true
    var resolverPort = 53
    @Published var busy = false
    @Published var active = false
    private(set) var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?
    let queue = DispatchQueue(label: "dev.ipv6lab.client")

    init() {
        observer = NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
        NETunnelProviderManager.loadAllFromPreferences { managers, _ in
            Task { @MainActor in
                self.manager = managers?.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == self.tunnelID }
                if let config = (self.manager?.protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration,
                   let echo = config["echoIPv4"] as? String { self.echoIPv4 = echo }
                if let config = (self.manager?.protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration {
                    self.resolver = DNSUpstream.configuredHost(config)
                    self.dnsOverrides = config["dnsOverrides"] as? String ?? ""
                    self.fullTunnel = config["fullTunnel"] as? Bool ?? true
                    self.resolverPort = config["resolverPort"] as? Int ?? 53
                }
                self.refresh()
#if DEBUG
                self.startDeviceRegressionIfRequested()
#endif
            }
        }
    }
    private var tunnelID: String { Bundle.main.bundleIdentifier! + ".PacketTunnel" }
    func start() {
        guard !busy else { return }
        resolver = resolver.trimmingCharacters(in: .whitespacesAndNewlines)
        guard resolver.isEmpty || DNSUpstream.isUsable(resolver) else { report = "请输入有效的上游 DNS IP 地址，或留空自动选择"; return }
        do { dnsOverrides = try DNSOverrides(dnsOverrides).text }
        catch { report = error.localizedDescription; return }
        busy = true
        NETunnelProviderManager.loadAllFromPreferences { managers, error in
            Task { @MainActor in
                if let error { self.fail(error); return }
                let manager = managers?.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == self.tunnelID } ?? NETunnelProviderManager()
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = self.tunnelID
                proto.serverAddress = "IPv6 Lab local gateway"
                let echo=PacketCodec.synthesize(self.echoIPv4) == nil ? "192.0.2.1" : self.echoIPv4
                proto.providerConfiguration = ["echoIPv4": echo, "resolver": self.resolver, "resolverPort": self.resolverPort, "resolverSelectionVersion": 1, "fullTunnel": self.fullTunnel, "dnsOverrides": self.dnsOverrides]
                proto.includeAllNetworks = self.fullTunnel
                proto.enforceRoutes = true
                proto.excludeLocalNetworks = false
                manager.protocolConfiguration = proto
                manager.localizedDescription = "IPv6 Lab · IPv6 测试"
                manager.isEnabled = true
                self.manager = manager
                manager.saveToPreferences { error in
                    Task { @MainActor in
                        if let error { self.fail(error); return }
                        manager.loadFromPreferences { error in
                            Task { @MainActor in
                                self.busy = false
                                if let error { self.fail(error); return }
                                do { try manager.connection.startVPNTunnel(); self.refresh() }
                                catch { self.fail(error) }
                            }
                        }
                    }
                }
            }
        }
    }
    func stop() { manager?.connection.stopVPNTunnel() }
    func refresh() {
        active = [.connected, .connecting, .reasserting].contains(manager?.connection.status ?? .invalid)
        switch manager?.connection.status {
        case .connected: status = fullTunnel ? "IPv6 测试已连接（仅 IPv6 隧道）" : "限定前缀模式已连接"
        case .connecting: status = "正在连接"
        case .disconnecting: status = "正在停止"
        default: status = "VPN 未连接"
        }
    }
    private func fail(_ error: Error) { busy = false; report = error.localizedDescription; refresh() }
    func snapshot() {
        guard let session = manager?.connection as? NETunnelProviderSession else { return }
        do {
            try session.sendProviderMessage(Data("status".utf8)) { data in
                Task { @MainActor in self.report = data.flatMap { String(data: $0, encoding: .utf8) } ?? "扩展未返回状态" }
            }
        } catch { fail(error) }
    }
}
