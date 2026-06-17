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

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class InviteController {

    private final ApiClient apiClient;

    @Value("${catapult.api.public-url:}")
    private String apiPublicUrl;

    public record Redemption(String inviteeTwitchId, Instant redeemedAt) {}

    @GetMapping("/invite")
    public String invitePage(@AuthenticationPrincipal CatapultWebUser user, Model model) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = apiClient.get("/api/invite", Map.class);
        if (data != null) {
            model.addAttribute("canInvite", Boolean.TRUE.equals(data.get("canInvite")));
            model.addAttribute("code", data.get("code"));
            model.addAttribute("inviteUrl", data.get("inviteUrl"));
            model.addAttribute("regeneratedAt", data.get("regeneratedAt"));
            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawRedemptions = (List<Map<String, String>>) data.get("redemptions");
            List<Redemption> redemptions = rawRedemptions == null ? List.of()
                : rawRedemptions.stream()
                    .map(m -> new Redemption(m.get("inviteeTwitchId"), Instant.parse(m.get("redeemedAt"))))
                    .toList();
            model.addAttribute("redemptions", redemptions);
        }
        return "invite";
    }

    @PostMapping("/invite/regenerate")
    public String regenerate() {
        apiClient.post("/api/invite/regenerate", null);
        return "redirect:/invite";
    }

    private static final java.util.regex.Pattern INVITE_CODE_PATTERN =
        java.util.regex.Pattern.compile("^[A-Z2-9]{1,12}$");

    @GetMapping("/join")
    public String joinPage(@RequestParam(required = false) String invite,
                           @RequestParam(required = false) String error,
                           @AuthenticationPrincipal CatapultWebUser user,
                           Model model) {
        if (user != null) {
            return "redirect:/channels";
        }
        String validatedCode = (invite != null && INVITE_CODE_PATTERN.matcher(invite).matches())
            ? invite : null;
        String oauthBase = (apiPublicUrl != null && !apiPublicUrl.isBlank()) ? apiPublicUrl : "";
        model.addAttribute("inviteCode", validatedCode);
        model.addAttribute("error", error);
        model.addAttribute("oauthBase", oauthBase);
        return "join";
    }
}
