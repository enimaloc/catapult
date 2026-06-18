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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class AdminConfigPageController {

    private final ApiClient apiClient;

    @GetMapping
    public String page() {
        return "admin/config";
    }

    @GetMapping("/api/catalog")
    @ResponseBody
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> entries = apiClient.get(
                "/api/admin/config",
                new ParameterizedTypeReference<>() {});
        return entries != null ? entries : List.of();
    }

    @PutMapping("/api/{key}")
    @ResponseBody
    public ResponseEntity<Void> apply(@PathVariable String key, @RequestBody Map<String, String> body) {
        boolean ok = apiClient.put("/api/admin/config/{key}", body, key);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }

    @DeleteMapping("/api/{key}")
    @ResponseBody
    public ResponseEntity<Void> clear(@PathVariable String key) {
        boolean ok = apiClient.delete("/api/admin/config/{key}", key);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }
}
