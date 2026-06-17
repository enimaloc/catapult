package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.security.CatapultWebUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class InviteController {

    private final ApiClient apiClient;

    @Value("${catapult.api.public-url:}")
    private String apiPublicUrl;

    @GetMapping("/invite")
    public String invitePage(@AuthenticationPrincipal CatapultWebUser user, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = apiClient.get("/api/invite", Map.class);
        if (data != null) {
            model.addAttribute("canInvite", Boolean.TRUE.equals(data.get("canInvite")));
            model.addAttribute("code", data.get("code"));
            model.addAttribute("inviteUrl", data.get("inviteUrl"));
            model.addAttribute("regeneratedAt", data.get("regeneratedAt"));
            model.addAttribute("redemptions", data.get("redemptions"));
        }
        return "invite";
    }

    @PostMapping("/invite/regenerate")
    public String regenerate() {
        apiClient.post("/api/invite/regenerate", null);
        return "redirect:/invite";
    }

    @GetMapping("/join")
    public String joinPage(@RequestParam(required = false) String invite,
                           @RequestParam(required = false) String error,
                           @AuthenticationPrincipal CatapultWebUser user,
                           Model model) {
        if (user != null) {
            return "redirect:/channels";
        }
        String oauthBase = (apiPublicUrl != null && !apiPublicUrl.isBlank()) ? apiPublicUrl : "";
        model.addAttribute("inviteCode", invite);
        model.addAttribute("error", error);
        model.addAttribute("oauthBase", oauthBase);
        return "join";
    }
}
