package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.security.CatapultWebUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final ApiClient apiClient;

    @GetMapping("/")
    public String home(@AuthenticationPrincipal CatapultWebUser principal, Model model) {
        if (principal != null) {
            return "redirect:/app";
        }
        Map<?, ?> providers = apiClient.get("/api/config/providers", Map.class);
        boolean showSteam     = providers != null && Boolean.TRUE.equals(providers.get("steam"));
        boolean showXbox      = providers != null && Boolean.TRUE.equals(providers.get("xbox"));
        boolean showBattlenet = providers != null && Boolean.TRUE.equals(providers.get("battlenet"));

        model.addAttribute("showSteam",      showSteam);
        model.addAttribute("showXbox",       showXbox);
        model.addAttribute("showBattlenet",  showBattlenet);
        model.addAttribute("hasAnySources",  showSteam || showXbox || showBattlenet);
        return "home";
    }
}
