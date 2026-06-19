package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
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
 * {@link ApiClient}. Mirrors the pattern used by
 * {@code NotificationsApiProxyController}.
 */
@Controller
@RequestMapping("/chat-commands/api")
@RequiredArgsConstructor
public class ChatCommandsApiProxyController {

    private final ApiClient apiClient;

    @GetMapping
    @ResponseBody
    public Map<String, Object> list() {
        Map<String, Object> data = apiClient.get(
            "/api/chat-commands", new ParameterizedTypeReference<>() {});
        return data != null ? data : Map.of();
    }

    @PostMapping("/presets/{key}")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> instantiatePreset(@PathVariable String key) {
        Map<String, Object> response = apiClient.post(
            "/api/chat-commands/presets/{key}", null, Map.class, key);
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
