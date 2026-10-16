package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.admin.WellKnownLocation;
import fr.enimaloc.catapult.service.wellknown.WellKnownFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The configured well-known files, read by catapult-web for its public /.well-known/** and root
 * routes (permitted to anyone, like the files themselves).
 */
@RestController
@RequiredArgsConstructor
public class ApiWellKnownController {
    private final WellKnownFileService service;

    @GetMapping("/api/well-known/serve")
    public ResponseEntity<byte[]> serve(@RequestParam(required = false) String location,
                                        @RequestParam(required = false) String path) {
        WellKnownLocation where;
        try {
            where = WellKnownLocation.valueOf(String.valueOf(location));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        return service.find(where, path)
                .map(f -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, f.getContentType())
                        .body(f.getContent().getBytes(StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
