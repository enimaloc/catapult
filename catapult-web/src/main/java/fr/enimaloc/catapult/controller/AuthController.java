package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.TokenResponse;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Completes the login: trades the OAuth code for a catapult-api JWT kept in the session. */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {
    private final ApiService apiService;

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
