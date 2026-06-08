package fr.enimaloc.catapult.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
@Profile("dev")
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevController {

    private final ApiClient apiClient;

    @GetMapping("/igdb")
    public String igdbExplorer(Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = apiClient.get("/api/dev/igdb", Map.class);
        if (data != null) {
            model.addAttribute("endpoints", data.get("endpoints"));
        }
        return "dev/igdb-explorer";
    }

    @PostMapping("/igdb/query")
    public String igdbQuery(@RequestParam String endpoint, @RequestParam String query, Model model) {
        IgdbQueryResult result = apiClient.post("/api/dev/igdb/query",
                Map.of("endpoint", endpoint, "query", query), IgdbQueryResult.class);
        model.addAttribute("igdbResult", result);
        return "fragments/igdb-result :: igdb-result";
    }

    @GetMapping("/template")
    public String template() {
        return "template";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IgdbQueryResult(String result, String error) {
        public boolean hasError() { return error != null; }
    }
}
