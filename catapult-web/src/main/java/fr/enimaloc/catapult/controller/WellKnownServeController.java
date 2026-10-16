package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.WellKnownContent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import fr.enimaloc.catapult.common.dto.admin.WellKnownHeader;
import fr.enimaloc.catapult.common.dto.admin.WellKnownResponseDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The well-known files the admin configured, served to anyone: under /.well-known/ (any depth), and
 * at the root for a dotted name (IndexController's pages have none). catapult-api decides what
 * exists; a path breaking its rules is a 404 there. The status and headers are the file's; nosniff is
 * always sent, and CSP {@code sandbox} unless the file sets its own Content-Security-Policy.
 */
@RestController
@RequiredArgsConstructor
public class WellKnownServeController {
    private static final String PREFIX = "/.well-known/";
    /** Browsers and crawlers ask for these constantly; they are never admin files. */
    private static final Set<String> RESERVED = Set.of("robots.txt", "sitemap.xml", "favicon.ico");

    private static final Set<String> REFUSED_HEADERS = Set.of("content-length", "transfer-encoding", "connection",
            "content-type", "set-cookie", "x-content-type-options");

    private final ApiService apiService;
    private final WellKnownCache cache;

    @GetMapping("/.well-known/**")
    public ResponseEntity<byte[]> wellKnown(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        // A bare /.well-known (no slash) is shorter than the prefix: nothing to serve.
        if (uri.length() <= PREFIX.length()) return ResponseEntity.notFound().build();
        return serve(WellKnownLocation.WELL_KNOWN, uri.substring(PREFIX.length()));
    }

    @GetMapping("/{name:.+\\..+}")
    public ResponseEntity<byte[]> root(@PathVariable String name) {
        if (RESERVED.contains(name)) return ResponseEntity.notFound().build();
        return serve(WellKnownLocation.ROOT, name);
    }

    private ResponseEntity<byte[]> serve(WellKnownLocation location, String path) {
        if (path.isEmpty() || path.contains("..") || path.contains("%")) return ResponseEntity.notFound().build();
        WellKnownContent content = cache.get(location, path, () -> apiService.wellKnown(location, path));
        if (content.status() == 404) return ResponseEntity.notFound().build();
        if (content.status() != 200) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        WellKnownResponseDto response = content.response();
        MediaType type;
        try {
            type = MediaType.parseMediaType(response.contentType());
            if (type.isWildcardType() || type.isWildcardSubtype()) type = MediaType.TEXT_PLAIN;
        } catch (RuntimeException e) {
            type = MediaType.TEXT_PLAIN;
        }
        HttpHeaders headers = new HttpHeaders();
        boolean ownCsp = false;
        List<WellKnownHeader> stored = response.headers() == null ? List.of() : response.headers();
        for (WellKnownHeader header : stored) {
            // Defence in depth: the API refuses these; a row that bypassed it must not plant cookies or mis-frame the answer.
            if (REFUSED_HEADERS.contains(header.name().toLowerCase(Locale.ROOT))) continue;
            if (header.name().equalsIgnoreCase("Content-Security-Policy")) ownCsp = true;
            headers.add(header.name(), header.value());
        }
        headers.set("X-Content-Type-Options", "nosniff");
        if (!ownCsp) headers.set("Content-Security-Policy", "sandbox");
        int status = response.status();
        boolean bodyless = status == 204 || status == 304;
        if (!bodyless) headers.setContentType(type);
        byte[] body = bodyless ? null : response.content() == null ? new byte[0] : response.content().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status).headers(headers).body(body);
    }
}
