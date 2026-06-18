package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Controller
@RequestMapping("/notifications/api")
@RequiredArgsConstructor
public class NotificationsApiProxyController {

    private final ApiClient apiClient;

    @GetMapping("/list")
    @ResponseBody
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> data = apiClient.get(
                "/api/notifications?page={page}&size={size}",
                new ParameterizedTypeReference<>() {},
                page, size);
        return data != null ? data : Map.of("content", java.util.List.of());
    }

    @GetMapping("/unread-count")
    @ResponseBody
    public long unreadCount() {
        Long count = apiClient.get("/api/notifications/unread-count", Long.class);
        return count != null ? count : 0L;
    }

    @PostMapping("/{id}/read")
    @ResponseBody
    public ResponseEntity<Void> markRead(@PathVariable String id) {
        apiClient.post("/api/notifications/{id}/read", null, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/read-all")
    @ResponseBody
    public ResponseEntity<Void> markAllRead() {
        apiClient.post("/api/notifications/read-all", null);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(java.time.Duration.ofMinutes(30).toMillis());
        apiClient.streamSse("/api/notifications/stream", emitter);
        return emitter;
    }
}
