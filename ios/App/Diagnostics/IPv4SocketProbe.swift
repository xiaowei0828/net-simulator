import Foundation
import Darwin

// A numeric NWConnection can be adapted by the system. AF_INET here makes the
// isolation check explicitly about IPv4 sockets, without DNS or synthesis.
enum IPv4SocketProbe {
    static func run(host: String, tcp: Bool, scoped: Bool) -> (received: Bool, detail: String) {
        let fd=socket(AF_INET, tcp ? SOCK_STREAM : SOCK_DGRAM, 0)
        guard fd>=0 else { return (false,"socket errno=\(errno)") }
        defer { close(fd) }
        var noSignal:Int32=1
        _ = setsockopt(fd,SOL_SOCKET,SO_NOSIGPIPE,&noSignal,socklen_t(MemoryLayout.size(ofValue:noSignal)))
        if scoped {
            var index=if_nametoindex("en0")
            guard index != 0, setsockopt(fd,IPPROTO_IP,IP_BOUND_IF,&index,socklen_t(MemoryLayout.size(ofValue:index)))==0 else {
                return (false,"setup-failed: IP_BOUND_IF errno=\(errno)")
            }
        }
        guard fcntl(fd,F_SETFL,O_NONBLOCK)==0 else { return (false,"setup-failed: nonblocking errno=\(errno)") }
        var address=sockaddr_in()
        address.sin_len=UInt8(MemoryLayout<sockaddr_in>.size); address.sin_family=sa_family_t(AF_INET)
        address.sin_port=UInt16(tcp ? 18080 : 18081).bigEndian
        guard inet_pton(AF_INET,host,&address.sin_addr)==1 else { return (false,"setup-failed: invalid IPv4") }
        let connected=withUnsafePointer(to:&address) {
            $0.withMemoryRebound(to:sockaddr.self,capacity:1) { connect(fd,$0,socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if connected<0 && errno != EINPROGRESS { return (false,"connect errno=\(errno)") }
        var descriptor=pollfd(fd:fd,events:Int16(POLLOUT),revents:0)
        guard poll(&descriptor,1,2000)>0 else { return (false,"connect timeout errno=\(errno)") }
        var socketError:Int32=0, length=socklen_t(MemoryLayout<Int32>.size)
        guard getsockopt(fd,SOL_SOCKET,SO_ERROR,&socketError,&length)==0, socketError==0 else {
            return (false,"connect SO_ERROR=\(socketError) errno=\(errno)")
        }
        let payload=Array(("IPv6Lab-"+UUID().uuidString).utf8)
        let sent=payload.withUnsafeBytes { send(fd,$0.baseAddress,$0.count,0) }
        guard sent==payload.count else { return (false,"send bytes=\(sent) errno=\(errno)") }
        if tcp { _ = shutdown(fd,SHUT_WR) }
        let deadline=ProcessInfo.processInfo.systemUptime+3
        var received=[UInt8]()
        while ProcessInfo.processInfo.systemUptime<deadline {
            descriptor.events=Int16(POLLIN); descriptor.revents=0
            let remaining=Int32(max(1,(deadline-ProcessInfo.processInfo.systemUptime)*1000))
            guard poll(&descriptor,1,remaining)>0 else { return (false,"receive timeout") }
            var buffer=[UInt8](repeating:0,count:4096)
            let count=recv(fd,&buffer,buffer.count,0)
            guard count>0 else { return (false,"receive bytes=\(count) errno=\(errno)") }
            received += buffer.prefix(count)
            if received.count>=payload.count || !tcp {
                return (true,received==payload ? "echo matched" : "echo mismatch")
            }
        }
        return (false,"receive timeout")
    }
}
