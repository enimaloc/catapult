package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.service.config.WebConfigCatalogService;
import fr.enimaloc.catapult.web.service.config.WebConfigEntry;
import fr.enimaloc.catapult.web.service.config.WebConfigOverrideService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class AdminConfigPageController {

    private static final String MODULE_API = "api";
    private static final String MODULE_WEB = "web";

    private final ApiClient apiClient;
    private final WebConfigCatalogService webConfigCatalogService;
    private final WebConfigOverrideService webConfigOverrideService;

    @GetMapping
    public String page() {
        return "admin/config";
    }

    @GetMapping("/api/catalog")
    @ResponseBody
    public List<Map<String, Object>> catalog(@RequestParam(defaultValue = MODULE_API) String module) {
        if (MODULE_WEB.equals(module)) {
            return webConfigCatalogService.catalog().stream()
                    .map(AdminConfigPageController::toMap)
                    .toList();
        }
        List<Map<String, Object>> entries = apiClient.get(
                "/api/admin/config",
                new ParameterizedTypeReference<>() {});
        return entries != null ? entries : List.of();
    }

    @PutMapping("/api/{key}")
    @ResponseBody
    public ResponseEntity<Void> apply(@PathVariable String key,
                                      @RequestBody Map<String, String> body,
                                      @RequestParam(defaultValue = MODULE_API) String module) {
        if (MODULE_WEB.equals(module)) {
            try {
                webConfigOverrideService.apply(key, body.get("value"));
                return ResponseEntity.noContent().build();
            } catch (ResponseStatusException e) {
                return ResponseEntity.status(e.getStatusCode()).build();
            }
        }
        boolean ok = apiClient.put("/api/admin/config/{key}", body, key);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }

    @DeleteMapping("/api/{key}")
    @ResponseBody
    public ResponseEntity<Void> clear(@PathVariable String key,
                                      @RequestParam(defaultValue = MODULE_API) String module) {
        if (MODULE_WEB.equals(module)) {
            try {
                webConfigOverrideService.clear(key);
                return ResponseEntity.noContent().build();
            } catch (ResponseStatusException e) {
                return ResponseEntity.status(e.getStatusCode()).build();
            }
        }
        boolean ok = apiClient.delete("/api/admin/config/{key}", key);
        return ResponseEntity.status(ok ? HttpStatus.NO_CONTENT : HttpStatus.BAD_GATEWAY).build();
    }

    /**
     * Convert the local {@link WebConfigEntry} record to a JSON-shape map that mirrors
     * the {@code ConfigEntry} JSON produced by catapult-api, so the admin UI JS can stay
     * uniform across modules.
     */
    private static Map<String, Object> toMap(WebConfigEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", e.key());
        m.put("value", e.value());
        m.put("secret", e.secret());
        m.put("restartRequired", e.restartRequired());
        m.put("overridden", e.overridden());
        m.put("taboo", e.taboo());
        m.put("source", e.source());
        return m;
    }
}
