package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
@RequestMapping("/connect/steam")
@RequiredArgsConstructor
public class SteamConnectController {

    private final ApiClient apiClient;

    @GetMapping
    public String connectSteam() {
        @SuppressWarnings("unchecked")
        Map<String, String> response = apiClient.get("/api/connect/steam/start", Map.class);
        if (response == null || response.get("redirectUrl") == null) {
            return "redirect:/channels";
        }
        return "redirect:" + response.get("redirectUrl");
    }

    @GetMapping("/close")
    public String closePage(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("steamError", error != null);
        return "closing";
    }

    @PostMapping("/disconnect")
    public String disconnect() {
        apiClient.post("/api/connect/steam/disconnect", null);
        return "redirect:/channels";
    }
}
