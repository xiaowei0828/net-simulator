import Foundation

struct DNSOverrides {
    let addresses: [String: String]
    let text: String
    enum Invalid: LocalizedError {
        case line(Int, String)
        var errorDescription: String? {
            switch self { case .line(let number, let reason): return "域名映射第 \(number) 行：\(reason)" }
        }
    }
    init(_ source: String = "") throws {
        var result=[String:String](), rows=[String]()
        for (index, raw) in source.components(separatedBy:"\n").enumerated() {
            let row=raw.trimmingCharacters(in:.whitespacesAndNewlines).replacingOccurrences(of:"：",with:":")
            if row.isEmpty { continue }
            let parts=row.components(separatedBy:":").map { $0.trimmingCharacters(in:.whitespacesAndNewlines) }
            guard parts.count==2 else { throw Invalid.line(index+1,"格式应为 域名:IPv4") }
            var name=parts[0].lowercased()
            if name.hasSuffix(".") { name.removeLast() }
            let labels=name.components(separatedBy:".")
            guard !name.isEmpty, name.utf8.count<=253, labels.allSatisfy({ label in
                !label.isEmpty && label.utf8.count<=63 && label.first != "-" && label.last != "-" &&
                label.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0==45 }
            }), !name.utf8.allSatisfy({ (48...57).contains($0) || $0==46 }) else {
                throw Invalid.line(index+1,"请输入完整域名，不含协议、路径或通配符；中文域名请使用 Punycode")
            }
            guard name != "ipv4only.arpa", name != "echo.ipv6lab.test" else { throw Invalid.line(index+1,"该域名保留用于工具自检") }
            let octets=parts[1].components(separatedBy:".")
            guard octets.count==4, octets.allSatisfy({ part in
                !part.isEmpty && part.utf8.allSatisfy { (48...57).contains($0) } &&
                (part=="0" || !part.hasPrefix("0")) && UInt8(part) != nil
            }), let first=UInt8(octets[0]), first>0, first != 127, first<224 else {
                throw Invalid.line(index+1,"请输入有效的单播 IPv4 地址（不支持回环地址）")
            }
            guard result[name]==nil else { throw Invalid.line(index+1,"域名重复：\(name)") }
            guard result.count<128 else { throw Invalid.line(index+1,"最多支持 128 条映射") }
            result[name]=parts[1]; rows.append("\(name):\(parts[1])")
        }
        addresses=result; text=rows.joined(separator:"\n")
    }
}
