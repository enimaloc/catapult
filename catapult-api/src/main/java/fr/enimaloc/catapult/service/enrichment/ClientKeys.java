package fr.enimaloc.catapult.service.enrichment;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The key a client's per-IP limits are counted under. An IPv6 client usually holds a whole {@code /64} and could
 * rotate through it, so all of it counts as one client; an IPv4-mapped IPv6 address counts as its IPv4 one.
 */
public final class ClientKeys {

    private static final int IPV6_PREFIX_BYTES = 8;
    /**
     * What an IPv6 literal (IPv4-mapped included) can be made of, starting the way {@link InetAddress#getByName}
     * reads as a literal: anything else is never handed to it, since it would look a host name up in the DNS.
     */
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:][0-9a-fA-F:.]*");

    private ClientKeys() {
    }

    /**
     * {@code address} itself for IPv4 (or anything unparseable), its {@code /64} prefix ({@code 2001:db8:0:1::/64})
     * for IPv6, the IPv4 address for an IPv4-mapped one ({@code ::ffff:192.0.2.1}).
     */
    public static String of(String address) {
        if (address == null || address.indexOf(':') < 0) {
            return address;
        }
        String literal = address.startsWith("[") && address.endsWith("]")
                ? address.substring(1, address.length() - 1) : address;
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (!IPV6_LITERAL.matcher(literal).matches()) {
            return address;
        }
        InetAddress parsed;
        try {
            // a hex-or-colon string with a colon is only ever parsed as an IPv6 literal, never looked up in the DNS
            parsed = InetAddress.getByName(literal);
        } catch (UnknownHostException | SecurityException e) {
            return address;
        }
        if (parsed instanceof Inet4Address) {
            return parsed.getHostAddress();
        }
        if (!(parsed instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = parsed.getAddress();
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < IPV6_PREFIX_BYTES; i += 2) {
            prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
        }
        return prefix.append(":/64").toString();
    }
}
