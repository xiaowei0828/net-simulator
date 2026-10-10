package dev.ipv6lab.core;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;

/** Returns unconnected sockets protected from, and bound outside, the VPN. */
public interface SocketProvider {
    Socket tcp() throws IOException;
    DatagramSocket udp() throws IOException;
}
