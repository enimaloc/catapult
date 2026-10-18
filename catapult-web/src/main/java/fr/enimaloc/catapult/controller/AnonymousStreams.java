package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.monitoring.InteractionMetrics;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Bounds the SSE streams opened without a session, which anyone may open: at most
 * {@link #MAX_PER_IP} per address, an IPv6 one counted by its /64 (a new one beyond that closing
 * its oldest, told so first as {@link SessionStreams} does), {@link #MAX_TOTAL}
 * overall (beyond that refused), each closed after {@link #TIMEOUT} (the browser reconnects).
 * The counterpart of {@link SessionStreams} for visitors who are not logged in.
 * <p>
 * They show in {@link InteractionMetrics} under the stream {@value #STREAM}: open, refused for
 * {@code capacity}, closed for a newer one of the same address.
 */
final class AnonymousStreams {
    static final int MAX_PER_IP = 8;
    static final int MAX_TOTAL = 1000;
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final String STREAM = "nav-anonymous";
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    /** Each address's streams, by {@link #addressKey}, oldest first; only touched within the map's compute calls. */
    private final Map<String, Deque<SseEmitter>> byIp = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final InteractionMetrics metrics;

    AnonymousStreams(InteractionMetrics metrics) {
        this.metrics = metrics;
        metrics.streams(STREAM, total::get);
    }

    /**
     * Counts {@code emitter} against {@code ip}, closing that address's oldest stream past its limit;
     * false when {@link #MAX_TOTAL} streams are already open, {@code emitter} then not counted.
     */
    boolean open(String ip, SseEmitter emitter) {
        if (total.incrementAndGet() > MAX_TOTAL) {
            total.decrementAndGet();
            metrics.streamRefused(STREAM, "capacity");
            return false;
        }
        List<SseEmitter> evicted = new ArrayList<>();
        byIp.compute(addressKey(ip), (key, streams) -> {
            Deque<SseEmitter> current = streams == null ? new ArrayDeque<>() : streams;
            current.addLast(emitter);
            while (current.size() > MAX_PER_IP) evicted.add(current.pollFirst());
            return current;
        });
        // Completed outside compute: its completion callback comes back to closed().
        for (SseEmitter oldest : evicted) {
            total.decrementAndGet();
            SessionStreams.evict(oldest);
            metrics.streamEvicted(STREAM);
        }
        return true;
    }

    /** {@code emitter} closed; counted once however often it is reported. */
    void closed(String ip, SseEmitter emitter) {
        AtomicBoolean removed = new AtomicBoolean();
        byIp.computeIfPresent(addressKey(ip), (key, streams) -> {
            removed.set(streams.remove(emitter));
            return streams.isEmpty() ? null : streams;
        });
        if (removed.get()) total.decrementAndGet();
    }

    /**
     * What an address is bounded by: an IPv4 address itself (also when IPv4-mapped), an IPv6 one's
     * /64, which a single host commonly owns whole; anything unparsable as is.
     */
    static String addressKey(String ip) {
        if (ip == null) return "";
        String literal = ip.startsWith("[") && ip.endsWith("]") ? ip.substring(1, ip.length() - 1) : ip;
        int zone = literal.indexOf('%');
        if (zone >= 0) literal = literal.substring(0, zone);
        // Only IPv6 literals reach InetAddress, which then parses without any lookup.
        if (literal.indexOf(':') < 0 || !IPV6_LITERAL.matcher(literal).matches()) return ip;
        try {
            InetAddress address = InetAddress.getByName(literal);
            if (address instanceof Inet4Address) return address.getHostAddress();
            return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
