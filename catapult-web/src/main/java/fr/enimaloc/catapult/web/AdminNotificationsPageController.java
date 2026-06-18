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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationsPageController {

    private final ApiClient apiClient;

    @GetMapping
    public String page() {
        return "admin/notifications";
    }

    @GetMapping("/api/list")
    @ResponseBody
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> data = apiClient.get(
                "/api/admin/notifications?page={page}&size={size}",
                new ParameterizedTypeReference<>() {},
                page, size);
        return data != null ? data : Map.of("content", java.util.List.of());
    }

    @PostMapping("/api/create")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> created = apiClient.post(
                "/api/admin/notifications", body, Map.class);
        return created != null
                ? ResponseEntity.status(HttpStatus.CREATED).body(created)
                : ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    @DeleteMapping("/api/{id}")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable String id) {
        boolean ok = apiClient.delete("/api/admin/notifications/{id}", id);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }
}
