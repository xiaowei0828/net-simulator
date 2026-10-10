#!/usr/bin/env python3
"""Bounded LAN fixtures: TCP half-close echo, UDP echo, DNS AAAA REFUSED -> A.
Only *.ipv6lab.test is served. No recursive resolver. Ctrl-C stops listeners.
"""
import argparse, socket, socketserver, struct, threading
p=argparse.ArgumentParser();p.add_argument('--address',required=True);args=p.parse_args()
def dns(wire,tcp=False):
    if len(wire)<17:return b''
    pos=12;labels=[]
    while pos<len(wire) and wire[pos]:
        size=wire[pos];pos+=1
        if size>63 or pos+size>len(wire):return b''
        labels.append(wire[pos:pos+size].decode('ascii'));pos+=size
    pos+=1
    if pos+4>len(wire):return b''
    name='.'.join(labels);qt,cls=struct.unpack('!HH',wire[pos:pos+4]);question=wire[12:pos+4]
    if not name.endswith('.ipv6lab.test'):rcode=3
    elif qt==28:rcode=5
    else:rcode=0
    answers=b'';count=0
    if rcode==0 and qt==1:
        # CNAME tests reconstruction of compressed owner names and synthesized targets.
        alias=b'\x06origin\x07ipv6lab\x04test\x00'
        answers=b'\xc0\x0c'+struct.pack('!HHIH',5,1,60,len(alias))+alias
        answers+=alias+struct.pack('!HHIH',1,1,120,4)+socket.inet_aton(args.address);count=2
    flags=0x8180|rcode
    if name.startswith('upstream.') and qt==1 and not tcp: flags|=0x200;answers=b'';count=0
    print(f'DNS {"TCP" if tcp else "UDP"} {name} type={qt} rcode={rcode} tc={bool(flags&0x200)}',flush=True)
    return wire[:2]+struct.pack('!HHHHH',flags,1,count,0,0)+question+answers
class DNSUDP(socketserver.BaseRequestHandler):
    def handle(self):
        data,s=self.request;response=dns(data)
        if response:s.sendto(response,self.client_address)
class DNSTCP(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(5)
        def read(n):
            b=b''
            while len(b)<n:
                a=self.request.recv(n-len(b))
                if not a:raise EOFError()
                b+=a
            return b
        try:
            size=struct.unpack('!H',read(2))[0];response=dns(read(size),True)
            self.request.sendall(struct.pack('!H',len(response))+response)
        except (EOFError,OSError):pass
class TCP(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(8);data=b''
        try:
            while len(data)<=65536:
                chunk=self.request.recv(8192)
                if not chunk:break
                data+=chunk
            if data.startswith(b'IPv6Lab-') and len(data)<=65536:
                self.request.sendall(data);print(f'TCP echo after FIN {len(data)} bytes {self.client_address}',flush=True)
        except OSError:pass
class UDP(socketserver.BaseRequestHandler):
    def handle(self):
        data,s=self.request
        if data.startswith(b'IPv6Lab-') and len(data)<=1452:
            s.sendto(data,self.client_address);print(f'UDP echo {len(data)} bytes {self.client_address}',flush=True)
class T(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
class U(socketserver.ThreadingUDPServer):allow_reuse_address=True;daemon_threads=True
servers=[T(('0.0.0.0',18080),TCP),U(('0.0.0.0',18081),UDP),T(('0.0.0.0',1053),DNSTCP),U(('0.0.0.0',1053),DNSUDP)]
for s in servers:threading.Thread(target=s.serve_forever,daemon=True).start()
print('READY: TCP18080 UDP18081 DNS1053',flush=True)
try:threading.Event().wait()
except KeyboardInterrupt:pass
finally:
    for s in servers:s.shutdown();s.server_close()
