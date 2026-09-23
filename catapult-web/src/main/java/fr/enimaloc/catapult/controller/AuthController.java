package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {
    private final ApiService apiService;

    @GetMapping("/oauth2/authorization/twitch")
    @Profile("mock")
    public String mockedAuth(Model model) {
        record JWT(String jwt, String features, String code) {
            public JWT(String jwt, String features) {
                this(jwt, features, jwt);
            }
        }
        model.addAttribute("jwts", List.of(
                new JWT("0", "Multiple account"),
                new JWT("1", "Online account"),
                new JWT("2", "Offline account")
        ));

        return "mock/jwt-select";
    }

    @GetMapping("/auth/callback")
    public String callback(@RequestParam String code, HttpSession session) {
        TokenResponse jwt = apiService.exchangeCode(code);
        if (jwt == null) {
            log.warn("Auth code exchange failed or expired");
            return "redirect:/login?error";
        }
        session.setAttribute(ApiClient.SESSION_JWT_KEY, jwt.token());
        return "redirect:/channels";
    }
}
