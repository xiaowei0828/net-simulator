#!/usr/bin/env python3
"""Local fixture for the dedicated emulator integration tests. No external service."""
import socketserver
import threading


class TcpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        while True:
            data = self.request.recv(16384)
            if not data:
                return
            self.request.sendall(data)


class UdpEcho(socketserver.BaseRequestHandler):
    def handle(self):
        data, sock = self.request
        sock.sendto(data, self.client_address)


class TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    with TcpServer(('127.0.0.1', 18080), TcpEcho) as tcp, socketserver.ThreadingUDPServer(('127.0.0.1', 18081), UdpEcho) as udp:
        threading.Thread(target=udp.serve_forever, daemon=True).start()
        print('Echo fixture ready: TCP 18080 / UDP 18081', flush=True)
        try:
            tcp.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            udp.shutdown()
