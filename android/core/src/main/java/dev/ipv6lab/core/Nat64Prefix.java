package dev.ipv6lab.core;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.Arrays;

/** A network-specific /96 prefix. No DNS resolution is performed when parsing. */
public final class Nat64Prefix {
    public static final String DEFAULT = "fd64:64:64::/96";
    private final byte[] bytes;

    public Nat64Prefix(String cidr) {
        if (cidr == null || !cidr.endsWith("/96"))
            throw new IllegalArgumentException("NAT64 前缀必须是 /96");
        InetAddress address = numeric(cidr.substring(0, cidr.length() - 3));
        if (!(address instanceof Inet6Address) || address.isMulticastAddress()
                || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress())
            throw new IllegalArgumentException("请输入有效的 IPv6 NAT64 前缀");
        bytes = address.getAddress();
        for (int i = 12; i < 16; i++)
            if (bytes[i] != 0) throw new IllegalArgumentException("/96 前缀的最后 32 位必须为 0");
    }

    public InetAddress synthesize(InetAddress v4) {
        if (v4.getAddress().length != 4) throw new IllegalArgumentException("Expected IPv4");
        byte[] result = bytes.clone();
        System.arraycopy(v4.getAddress(), 0, result, 12, 4);
        return fromBytes(result);
    }

    public boolean contains(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length != 16) return false;
        for (int i = 0; i < 12; i++) if (bytes[i] != raw[i]) return false;
        return true;
    }

    public InetAddress translate(InetAddress address) {
        if (address.getAddress().length != 16)
            throw new IllegalArgumentException("Direct IPv4 is blocked");
        return contains(address) ? fromBytes(Arrays.copyOfRange(address.getAddress(), 12, 16)) : address;
    }

    public static InetAddress numeric(String text) {
        // InetAddress.getByName must never resolve a hostname through the VPN here.
        if (text == null || text.isEmpty() || !text.matches("[0-9a-fA-F:.]+"))
            throw new IllegalArgumentException("请输入数字 IP 地址，不支持域名或 zone ID");
        if (!text.contains(":")) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("IPv4 必须是四段十进制地址");
            byte[] raw = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("无效 IPv4 地址");
                int n = Integer.parseInt(parts[i]);
                if (n > 255) throw new IllegalArgumentException("无效 IPv4 地址");
                raw[i] = (byte) n;
            }
            return fromBytes(raw);
        }
        try { return InetAddress.getByName(text); }
        catch (UnknownHostException e) { throw new IllegalArgumentException("无效 IPv6 地址", e); }
    }

    public static InetAddress fromBytes(byte[] raw) {
        try { return InetAddress.getByAddress(raw); }
        catch (UnknownHostException e) { throw new IllegalArgumentException(e); }
    }
}
