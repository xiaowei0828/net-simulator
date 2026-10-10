package dev.ipv6lab;

import android.content.Context;
import android.content.SharedPreferences;
import dev.ipv6lab.core.LocalGateway;
import dev.ipv6lab.core.Nat64Prefix;
import java.util.HashSet;
import java.util.Set;

public final class LabConfig {
    public final String prefix;
    public final String resolver;
    public final dev.ipv6lab.core.DnsOverrides dnsOverrides;
    public final Set<String> packages;
    public LabConfig(String prefix, String resolver, Set<String> packages) {
        this(prefix, resolver, packages, "");
    }
    public LabConfig(String prefix, String resolver, Set<String> packages, String overrides) {
        this.dnsOverrides = new dev.ipv6lab.core.DnsOverrides(overrides);
        Nat64Prefix parsed = new Nat64Prefix(prefix.trim());
        if (parsed.contains(Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS)))
            throw new IllegalArgumentException("NAT64 前缀不能与虚拟接口网段重叠");
        if (!resolver.trim().isEmpty()) {
            java.net.InetAddress ip = Nat64Prefix.numeric(resolver.trim());
            if (ip.isAnyLocalAddress() || ip.isMulticastAddress() || ip.isLoopbackAddress())
                throw new IllegalArgumentException("上游 DNS 必须是可达的单播 IP");
        }
        this.prefix = prefix.trim(); this.resolver = resolver.trim(); this.packages = new HashSet<>(packages);
    }
    public static LabConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        return new LabConfig(prefs.getString("prefix", Nat64Prefix.DEFAULT), prefs.getString("resolver", ""),
                prefs.getStringSet("packages", new HashSet<>()), prefs.getString("dnsOverrides", ""));
    }
    public void save(Context context) {
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("prefix", prefix)
                .putString("resolver", resolver).putString("dnsOverrides", dnsOverrides.text).putStringSet("packages", packages).apply();
    }
}
