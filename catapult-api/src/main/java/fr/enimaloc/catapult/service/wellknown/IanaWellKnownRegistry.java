package fr.enimaloc.catapult.service.wellknown;

import fr.enimaloc.catapult.common.dto.admin.IanaRegistryDto;
import fr.enimaloc.catapult.common.dto.admin.IanaWellKnownDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * IANA's Well-Known URIs registry for the admin's Well-known tab: downloaded from iana.org and kept
 * 24 h; when that fails, the copy bundled with catapult-api, iana.org being asked again 10 min later
 * at the earliest (not on every tab opening).
 */
@Slf4j
@Service
public class IanaWellKnownRegistry {
    static final String URL = "https://www.iana.org/assignments/well-known-uris/well-known-uris-1.csv";
    static final Duration TTL = Duration.ofHours(24);
    static final Duration RETRY = Duration.ofMinutes(10);
    private static final String SNAPSHOT = "iana/well-known-uris-1.csv";
    private static final String HEADER = "URI Suffix,";

    private final Supplier<String> download;
    private final Clock clock;
    private IanaRegistryDto live;
    private Instant liveUntil = Instant.MIN;
    private Instant retryAt = Instant.MIN;
    private IanaRegistryDto snapshot;

    @Autowired
    public IanaWellKnownRegistry(RestClient.Builder builder) {
        this(downloader(builder), Clock.systemUTC());
    }

    IanaWellKnownRegistry(Supplier<String> download, Clock clock) {
        this.download = download;
        this.clock = clock;
    }

    private static Supplier<String> downloader(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder.clone().requestFactory(factory).build();
        return () -> client.get().uri(URL).retrieve().body(String.class);
    }

    public synchronized IanaRegistryDto registry() {
        Instant now = clock.instant();
        if (live != null && now.isBefore(liveUntil)) return live;
        if (now.isBefore(retryAt)) return live != null ? live : snapshot();
        try {
            String csv = download.get();
            if (csv == null || !csv.startsWith(HEADER)) throw new IllegalStateException("not the registry's CSV");
            live = new IanaRegistryDto(parse(csv), "LIVE");
            liveUntil = now.plus(TTL);
            return live;
        } catch (RuntimeException e) {
            log.warn("IANA well-known registry unavailable, using the bundled copy: {}", e.getMessage());
            retryAt = now.plus(RETRY);
            return live != null ? live : snapshot();
        }
    }

    private IanaRegistryDto snapshot() {
        if (snapshot == null) {
            try {
                String csv = new ClassPathResource(SNAPSHOT).getContentAsString(StandardCharsets.UTF_8);
                snapshot = new IanaRegistryDto(parse(csv), "SNAPSHOT");
            } catch (IOException | RuntimeException e) {
                log.error("Bundled IANA well-known registry unreadable", e);
                return new IanaRegistryDto(List.of(), "SNAPSHOT");
            }
        }
        return snapshot;
    }

    /** The registry's rows (header skipped): RFC 4180 fields, quotes and "" escapes included. */
    static List<IanaWellKnownDto> parse(String csv) {
        List<IanaWellKnownDto> entries = new ArrayList<>();
        List<List<String>> rows = rows(csv);
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() < 4 || row.getFirst().isBlank()) continue;
            entries.add(new IanaWellKnownDto(row.get(0).trim(), unbracket(row.get(1)), row.get(2).trim(), unbracket(row.get(3))));
        }
        return List.copyOf(entries);
    }

    private static String unbracket(String value) {
        String v = value.trim();
        return v.startsWith("[") && v.endsWith("]") ? v.substring(1, v.length() - 1) : v;
    }

    private static List<List<String>> rows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
