import Foundation

enum DNSUpstream {
    // Empty means automatic. Migrate the previous built-in default, including
    // preferences opened directly from Settings without launching the app.
    static func configuredHost(_ config: [String: Any]) -> String {
        let host = (config["resolver"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if config["resolverSelectionVersion"] == nil && host == "1.1.1.1" { return "" }
        return host
    }

    static func isUsable(_ host: String) -> Bool {
        guard !host.isEmpty else { return false }
        if let bytes = PacketCodec.synthesize(host) {
            let ipv4 = Array(bytes.suffix(4))
            return ipv4[0] != 0 && ipv4[0] != 127 && ipv4[0] < 224
        }
        let parts = host.split(separator: "%", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count), parts.count == 1 || !parts[1].isEmpty,
              let bytes = PacketCodec.bytes(String(parts[0])) else { return false }
        guard bytes != [UInt8](repeating: 0, count: 16),
              bytes != [UInt8](repeating: 0, count: 15) + [1], bytes[0] != 0xff,
              bytes != PacketCodec.bytes(PacketCodec.dnsAddress),
              Array(bytes.prefix(12)) != Array(PacketCodec.bytes(PacketCodec.prefix)!.prefix(12)) else { return false }
        // Link-local DNS needs the physical interface scope.
        return !(bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80) || parts.count == 2
    }

    static func select(_ servers: [String]) -> String? {
        let usable = servers.filter(isUsable)
        return usable.first(where: { PacketCodec.synthesize($0) != nil }) ?? usable.first
    }
}
