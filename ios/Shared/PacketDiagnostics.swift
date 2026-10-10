import Foundation

enum PacketDiagnostics {
    static func ipv4(_ data: Data) -> String {
        let b=[UInt8](data)
        guard b.count>=20, b[0]>>4==4 else { return "invalid-header" }
        let size=Int(b[0]&15)*4, total=PacketCodec.u16(b,2)
        guard size>=20, size<=b.count, total>=size, total<=b.count else { return "invalid-length" }
        let fragment=PacketCodec.u16(b,6)&0x1fff
        var result="src=\(PacketCodec.address(Array(b[12..<16]))) dst=\(PacketCodec.address(Array(b[16..<20]))) protocol=\(b[9]) fragmentOffset=\(fragment)"
        if fragment==0, [6,17].contains(b[9]), size+4<=total {
            result += " srcPort=\(PacketCodec.u16(b,size)) dstPort=\(PacketCodec.u16(b,size+2))"
        }
        return result
    }
}
