#!/usr/bin/env python3
"""Temporary LAN echo fixture for a physical iPhone; Ctrl-C to stop."""
import socket
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind(('0.0.0.0', 18081))
    print('UDP echo listening on port 18081. Configure the Mac LAN IPv4 in the iOS app.', flush=True)
    try:
        while True:
            data, peer = sock.recvfrom(2048)
            if data.startswith(b'IPv6Lab-') and len(data) <= 1452:
                sock.sendto(data, peer)
                print(f'echo {len(data)} bytes to {peer[0]}:{peer[1]}', flush=True)
    except KeyboardInterrupt:
        pass
