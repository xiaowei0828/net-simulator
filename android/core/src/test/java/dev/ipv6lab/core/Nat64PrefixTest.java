package dev.ipv6lab.core;
import org.junit.Test;
import static org.junit.Assert.*;

public class Nat64PrefixTest {
    @Test public void roundTripsIpv4() {
        Nat64Prefix p = new Nat64Prefix(Nat64Prefix.DEFAULT);
        for (String ip : new String[]{"192.0.2.1", "10.0.0.1", "255.255.255.255"})
            assertEquals(Nat64Prefix.numeric(ip), p.translate(p.synthesize(Nat64Prefix.numeric(ip))));
    }
    @Test public void nativeV6IsUnchanged() {
        Nat64Prefix p = new Nat64Prefix(Nat64Prefix.DEFAULT);
        assertEquals(Nat64Prefix.numeric("2001:db8::1"), p.translate(Nat64Prefix.numeric("2001:db8::1")));
    }
    @Test(expected=IllegalArgumentException.class) public void refusesDirectIpv4() { new Nat64Prefix(Nat64Prefix.DEFAULT).translate(Nat64Prefix.numeric("192.0.2.1")); }
    @Test(expected=IllegalArgumentException.class) public void refusesNonZeroHostBits() { new Nat64Prefix("fd64::1/96"); }
    @Test(expected=IllegalArgumentException.class) public void refusesOtherPrefixLengths() { new Nat64Prefix("fd64::/64"); }
    @Test(expected=IllegalArgumentException.class) public void refusesHostnames() { Nat64Prefix.numeric("example.com"); }
    @Test(expected=IllegalArgumentException.class) public void refusesShorthandIpv4() { Nat64Prefix.numeric("127.1"); }
    @Test(expected=IllegalArgumentException.class) public void refusesMulticastPrefix() { new Nat64Prefix("ff00::/96"); }
}
