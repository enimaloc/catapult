package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiHealthProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code GET /api/health}: 200 {@code {"status":"UP"}} while catapult-api answers, 503
 * {@code {"status":"DOWN"}} otherwise. Served here, not relayed (it is not one of ApiRelayController's paths),
 * so it tells a script whether the API is up even when it is not. Open to any origin: it holds nothing private.
 */
@RestController
public class ApiHealthController {
    private final ApiHealthProbe probe;

    @Autowired
    public ApiHealthController(ApiHealthProbe probe) {
        this.probe = probe;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean up = probe.up();
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .header("Access-Control-Allow-Origin", "*")
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
