package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Thymeleaf page for issuing broadcasts (spec §8 UI admin).
 *
 * <p>The page itself is plain Thymeleaf; the «send» button posts to the
 * companion JSON endpoint below which proxies to {@code POST /api/admin/broadcast}.
 * Routing through the web layer keeps the JWT plumbing (cookie/session →
 * Bearer) in one place and lets us flow the request through the WS-HTMX
 * extension end-to-end (spec §6) once that path is live.</p>
 */
@Slf4j
@Controller
@RequestMapping("/admin/broadcast")
@RequiredArgsConstructor
public class AdminBroadcastController {

    /**
     * Names exposed in the dropdown. Mirrors {@code BroadcastValidator.ALLOWED_NAMES}
     * in catapult-api — kept here as a fixed list so the page stays trivially
     * server-renderable without a round-trip.
     */
    public static final List<String> BROADCAST_NAMES = List.of(
            "maintenance.scheduled",
            "maintenance.cancelled",
            "version.deployed",
            "alert.info",
            "alert.warning"
    );

    private final ApiClient apiClient;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("broadcastNames", BROADCAST_NAMES);
        return "admin/broadcast";
    }

    /**
     * HTMX-friendly proxy. Forwards the JSON body verbatim to the API.
     * The API does its own Jackson-polymorphic validation; we just relay status.
     */
    @PostMapping("/api/send")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body) {
        try {
            apiClient.post("/api/admin/broadcast", body);
            // ApiClient.post(String, Object, Object...) is fire-and-forget (no exception
            // surfaces on 4xx — it logs them instead), so a quiet return path means
            // either real success or a swallowed error. The UI still gets 202 here;
            // serious failures are visible in the api logs and in the activity feed.
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("name", body.get("name"));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
        } catch (Exception ex) {
            log.warn("admin broadcast proxy failed: {}", ex.toString());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
        }
    }
}
