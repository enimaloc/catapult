package fr.enimaloc.catapult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A GET over a raw socket bound to a chosen local address (127.0.0.2 is a different peer than the loopback, which
 * {@code java.net.http} cannot be told to be), to tell trusted proxies from other clients on a real server.
 */
public final class RawHttp {

    private RawHttp() {}

    public record RawResponse(int status, Map<String, String> headers, String body) {}

    public static RawResponse get(int port, String fromAddress, String path, Map<String, String> headers) throws Exception {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(fromAddress), 0));
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setSoTimeout(10000);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
            headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            in.transferTo(raw);
            String text = raw.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String head = split < 0 ? text : text.substring(0, split);
            String body = split < 0 ? "" : text.substring(split + 4);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> parsed = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    parsed.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
                }
            }
            return new RawResponse(status, parsed, body.trim());
        }
    }
}
