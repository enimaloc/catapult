package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class HomeController {

    @Value("${steam.enabled:false}")     private boolean steamEnabled;
    @Value("${steam.api-key:}")          private String steamApiKey;
    @Value("${xbox.enabled:false}")      private boolean xboxEnabled;
    @Value("${battlenet.enabled:false}") private boolean battlenetEnabled;

    @Autowired(required = false)
    private SteamApiKeyRotator rotator;

    @GetMapping("/")
    public String home(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        if (principal != null) {
            return "redirect:/app";
        }
        boolean showSteam = steamEnabled && steamKeyAvailable();
        boolean showXbox = xboxEnabled;
        boolean showBattlenet = battlenetEnabled;
        model.addAttribute("showSteam", showSteam);
        model.addAttribute("showXbox", showXbox);
        model.addAttribute("showBattlenet", showBattlenet);
        model.addAttribute("hasAnySources", showSteam || showXbox || showBattlenet);
        return "home";
    }

    private boolean steamKeyAvailable() {
        if (rotator != null) return rotator.nextKey().isPresent();
        return !steamApiKey.isBlank();
    }
}
