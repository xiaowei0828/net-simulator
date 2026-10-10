import Foundation
import Darwin

// Public packetFlow uses bare IP packets, without utun's four-byte family header.
// UDP packet codec; TCP is handled separately by the C/lwIP bridge.
enum PacketCodec {
    static let mtu = 1500
    static let maxUDPPayload = mtu - 40 - 8
    static let dnsAddress = "fd64:64:64:1::53"
    static let prefix = "fd64:64:64::"
    static func bytes(_ address: String) -> [UInt8]? {
        var value = in6_addr()
        guard inet_pton(AF_INET6, address, &value) == 1 else { return nil }
        return withUnsafeBytes(of: value) { Array($0) }
    }
    static func synthesize(_ ipv4: String) -> [UInt8]? {
        var value = in_addr()
        guard inet_pton(AF_INET, ipv4, &value) == 1, let base = bytes(prefix) else { return nil }
        return Array(base.prefix(12)) + withUnsafeBytes(of: value) { Array($0) }
    }
    static func address(_ bytes: [UInt8]) -> String {
        var output = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        let family = bytes.count == 4 ? AF_INET : AF_INET6
        guard bytes.count == 4 || bytes.count == 16 else { return "invalid" }
        let ok = bytes.withUnsafeBytes { inet_ntop(family, $0.baseAddress!, &output, socklen_t(output.count)) }
        return ok == nil ? "invalid" : String(cString: output)
    }
    static func u16(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) << 8 | Int(b[i + 1]) }
    static func word(_ value: Int) -> [UInt8] { [UInt8((value >> 8) & 255), UInt8(value & 255)] }
    static func checksum(_ b: [UInt8]) -> UInt16 {
        var sum: UInt32 = 0
        for i in stride(from: 0, to: b.count, by: 2) {
            sum += UInt32(b[i]) << 8
            if i + 1 < b.count { sum += UInt32(b[i + 1]) }
        }
        while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16) }
        return ~UInt16(sum)
    }
    struct Datagram {
        let source: [UInt8]
        let destination: [UInt8]
        let sourcePort: Int
        let destinationPort: Int
        let payload: [UInt8]
        init?(_ data: Data) {
            let b = [UInt8](data)
            guard b.count >= 48, b[0] >> 4 == 6, b[6] == 17,
                  PacketCodec.u16(b, 4) == b.count - 40,
                  PacketCodec.u16(b, 44) == b.count - 40,
                  PacketCodec.u16(b, 46) != 0 else { return nil }
            let pseudo = Array(b[8..<40]) + [0, 0] + PacketCodec.word(b.count - 40) + [0, 0, 0, 17]
            guard PacketCodec.checksum(pseudo + Array(b[40...])) == 0 else { return nil }
            source = Array(b[8..<24]); destination = Array(b[24..<40])
            sourcePort = PacketCodec.u16(b, 40); destinationPort = PacketCodec.u16(b, 42)
            payload = Array(b[48...])
        }
        func reply(_ payload: [UInt8]) -> Data {
            PacketCodec.udp(source: destination, destination: source, sourcePort: destinationPort,
                          destinationPort: sourcePort, payload: payload)
        }
    }
    static func udp(source: [UInt8], destination: [UInt8], sourcePort: Int,
                    destinationPort: Int, payload: [UInt8]) -> Data {
        precondition(source.count == 16 && destination.count == 16 && payload.count <= maxUDPPayload)
        let length = payload.count + 8
        var udp = word(sourcePort) + word(destinationPort) + word(length) + [0, 0] + payload
        let pseudo = source + destination + [0, 0] + word(length) + [0, 0, 0, 17]
        let sum = checksum(pseudo + udp)
        udp.replaceSubrange(6..<8, with: word(Int(sum == 0 ? 0xffff : sum)))
        var packet: [UInt8] = [0x60, 0, 0, 0]
        packet += word(length); packet += [17, 64]
        packet += source; packet += destination; packet += udp
        return Data(packet)
    }
    static func query(_ name: String, id: Int = 0x6464) -> [UInt8] {
        var wire = word(id) + [1, 0, 0, 1, 0, 0, 0, 0, 0, 0]
        for label in name.split(separator: ".") { wire += [UInt8(label.utf8.count)] + label.utf8 }
        return wire + [0, 0, 28, 0, 1]
    }
    // Deliberately local fixtures: do not claim to validate upstream DNS64 yet.
    static func dnsReply(_ query: [UInt8], echoIPv4: String) -> [UInt8]? {
        guard query.count >= 17, query[2] & 0xf8 == 0, u16(query, 4) == 1 else { return nil }
        var end = 12; var labels = [String]()
        while end < query.count {
            let count = Int(query[end]); end += 1
            if count == 0 { break }
            guard count <= 63, end + count < query.count,
                  let label = String(bytes: query[end..<(end + count)], encoding: .ascii) else { return nil }
            labels.append(label); end += count
        }
        guard end + 4 <= query.count, end - 12 <= 255 else { return nil }
        let type = u16(query, end), cls = u16(query, end + 2)
        let name = labels.joined(separator: ".").lowercased()
        let known = name == "ipv4only.arpa" || name == "echo.ipv6lab.test"
        let addresses = name == "ipv4only.arpa" ? ["192.0.0.170", "192.0.0.171"] : [echoIPv4]
        let values = known && cls == 1 && type == 28 ? addresses.compactMap(synthesize) : []
        var out = Array(query.prefix(2)) + [0x80 | (query[2] & 1), UInt8(known ? 0 : 3)]
        out += [0, 1] + word(values.count) + [0, 0, 0, 0] + Array(query[12..<(end + 4)])
        for value in values { out += [0xc0, 0x0c, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16] + value }
        return out
    }
}
