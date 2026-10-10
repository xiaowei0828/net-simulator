package dev.ipv6lab.core;

import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Immutable exact-name overrides, parsed without network access. */
public final class DnsOverrides {
    private final Map<String, InetAddress> addresses;
    public final String text;
    public DnsOverrides(String source) {
        Map<String, InetAddress> result = new LinkedHashMap<>();
        StringBuilder normalized = new StringBuilder();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String row = lines[i].trim().replace('：', ':');
            if (row.isEmpty()) continue;
            try {
                String[] parts = row.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("格式应为 域名:IPv4");
                String name = parts[0].trim().toLowerCase(Locale.ROOT);
                if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
                if (name.isEmpty() || name.length() > 253 || name.matches("[0-9.]+"))
                    throw new IllegalArgumentException("请输入有效域名");
                for (String label : name.split("\\.", -1))
                    if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
                        throw new IllegalArgumentException("域名不能包含协议、路径或通配符；中文域名请使用 Punycode");
                if (name.equals("ipv4only.arpa") || name.equals("echo.ipv6lab.test"))
                    throw new IllegalArgumentException("该域名保留用于工具自检");
                InetAddress ip = Nat64Prefix.numeric(parts[1].trim());
                byte[] bytes = ip.getAddress();
                if (bytes.length != 4 || (bytes[0] & 255) == 0 || (bytes[0] & 255) == 127 || (bytes[0] & 255) >= 224)
                    throw new IllegalArgumentException("请输入有效的单播 IPv4 地址（不支持回环地址）");
                if (result.containsKey(name)) throw new IllegalArgumentException("域名重复：" + name);
                if (result.size() >= 128) throw new IllegalArgumentException("最多支持 128 条映射");
                result.put(name, ip);
                if (normalized.length() > 0) normalized.append('\n');
                normalized.append(name).append(':').append(ip.getHostAddress());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("域名映射第 " + (i + 1) + " 行：" + e.getMessage(), e);
            }
        }
        addresses = Collections.unmodifiableMap(result); text = normalized.toString();
    }
    public InetAddress lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.endsWith(".")) key = key.substring(0, key.length() - 1);
        return addresses.get(key);
    }
    public int size() { return addresses.size(); }
}
