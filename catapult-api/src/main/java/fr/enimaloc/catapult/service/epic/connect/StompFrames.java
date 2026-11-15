package fr.enimaloc.catapult.service.epic.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The STOMP 1.2 frames Catapult exchanges with Epic Connect: the two it sends and a parser for what comes back.
 */
public final class StompFrames {

    /** A client heart-beat is a bare end-of-line. */
    public static final String HEARTBEAT = "\n";

    private StompFrames() {
    }

    public record Frame(String command, Map<String, String> headers, String body) {
    }

    /** Accepts STOMP 1.2 and promises a heart-beat every 30 s (the server asks for at most 45 s). */
    public static String connect() {
        return "CONNECT\naccept-version:1.2\nheart-beat:30000,0\n\n\0";
    }

    public static String subscribe(String botAccountId) {
        return "SUBSCRIBE\nid:sub-0\ndestination:_/account/" + botAccountId + "\n\n\0";
    }

    /**
     * Parses one received frame; empty for a heart-beat or a blank text. The frame ends at its NUL: the end-of-lines
     * STOMP 1.2 allows after it are dropped. The first occurrence of a repeated header wins, as STOMP 1.2 specifies.
     */
    public static Optional<Frame> parse(String raw) {
        int nul = raw.indexOf('\0');
        String text = nul < 0 ? raw : raw.substring(0, nul);
        int start = 0;
        while (start < text.length() && (text.charAt(start) == '\n' || text.charAt(start) == '\r')) {
            start++;
        }
        if (start == text.length()) {
            return Optional.empty();
        }
        text = text.substring(start);

        int headerEnd = text.indexOf("\n\n");
        String head = headerEnd < 0 ? text : text.substring(0, headerEnd);
        String body = headerEnd < 0 ? "" : text.substring(headerEnd + 2);

        String[] lines = head.split("\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(unescape(line.substring(0, colon)), unescape(line.substring(colon + 1)));
            }
        }
        String command = lines[0].endsWith("\r") ? lines[0].substring(0, lines[0].length() - 1) : lines[0];
        return Optional.of(new Frame(command, headers, body));
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 'c' -> out.append(':');
                    case '\\' -> out.append('\\');
                    default -> out.append(c).append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
