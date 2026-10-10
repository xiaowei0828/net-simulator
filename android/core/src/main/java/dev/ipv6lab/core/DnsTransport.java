package dev.ipv6lab.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.function.Consumer;

/** Protected DNS upstream, with connected UDP and TCP fallback on TC. */
public final class DnsTransport implements Dns64.Exchange {
    private final SocketProvider sockets;
    private final InetSocketAddress upstream;
    private final Consumer<String> logger;
    public DnsTransport(SocketProvider sockets, InetSocketAddress upstream) {
        this(sockets, upstream, message -> {});
    }
    public DnsTransport(SocketProvider sockets, InetSocketAddress upstream, Consumer<String> logger) {
        this.sockets = sockets; this.upstream = upstream; this.logger = logger;
    }
    @Override public byte[] query(byte[] request) throws IOException {
        String context = "DNS transport id=" + (request.length < 2 ? -1 : ((request[0] & 255) << 8) | (request[1] & 255))
                + " upstream=" + upstream;
        long started = System.nanoTime();
        logger.accept(context + " send UDP");
        try {
            return exchange(request, context);
        } catch (IOException e) {
            // The exchange includes the TCP stage in errors after a truncated UDP reply.
            throw new IOException(context + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000
                    + " cause=" + e, e);
        }
    }
    private byte[] exchange(byte[] request, String context) throws IOException {
        byte[] answer;
        try (DatagramSocket socket = sockets.udp()) {
            socket.connect(upstream);
            socket.setSoTimeout(4000);
            socket.send(new DatagramPacket(request, request.length));
            DatagramPacket packet = new DatagramPacket(new byte[65535], 65535);
            socket.receive(packet);
            answer = Arrays.copyOf(packet.getData(), packet.getLength());
        } catch (IOException e) { throw new IOException("UDP failed: " + e, e); }
        if (answer.length < 12) throw new IOException("Short DNS response");
        if ((answer[2] & 2) != 0) {
            logger.accept(context + " UDP tc=true; fallback TCP");
            try (Socket socket = sockets.tcp()) {
                socket.connect(upstream, 4000);
                socket.setSoTimeout(4000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeShort(request.length); out.write(request); out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                answer = new byte[in.readUnsignedShort()]; in.readFully(answer);
            } catch (IOException e) { throw new IOException("TCP fallback failed: " + e, e); }
        }
        logger.accept(context + " received bytes=" + answer.length);
        return answer;
    }
}
