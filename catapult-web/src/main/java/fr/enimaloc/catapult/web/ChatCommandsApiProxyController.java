package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.UUID;

/**
 * Server-side proxy for the chat-commands API. The browser hits these URLs
 * on catapult-web (since nginx routes {@code /} to catapult-web), and this
 * controller forwards them to catapult-api via the JWT-bearing
 * {@link ApiClient}.
 */
@Slf4j
@Controller
@RequestMapping("/chat-commands/api")
@RequiredArgsConstructor
public class ChatCommandsApiProxyController {

    private final ApiClient apiClient;

    @GetMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> list() {
        Map<String, Object> data = apiClient.get(
            "/api/chat-commands", new ParameterizedTypeReference<>() {});
        if (data == null) {
            // ApiClient swallows errors and returns null. Re-surface as 502 so
            // the browser sees something instead of "{}", and log a clear hint.
            log.warn("[chat-commands proxy] GET /api/chat-commands returned null — likely 403 (experiment gate) or 5xx");
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Upstream call failed (check catapult-api logs; likely experiment gate)"));
        }
        return ResponseEntity.ok(data);
    }

    @PostMapping("/presets/{key}")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> instantiatePreset(@PathVariable String key) {
        Map<String, Object> response = apiClient.post(
            "/api/chat-commands/presets/{key}", null, Map.class, key);
        if (response == null) {
            log.warn("[chat-commands proxy] POST /api/chat-commands/presets/{} returned null", key);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Failed to instantiate preset (check catapult-api logs)"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping
    @ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> response = apiClient.post(
            "/api/chat-commands", body, Map.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        boolean ok = apiClient.put("/api/chat-commands/{id}", body, id);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        boolean ok = apiClient.delete("/api/chat-commands/{id}", id);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }
}
