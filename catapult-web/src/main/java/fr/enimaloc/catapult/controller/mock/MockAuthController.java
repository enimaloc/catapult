package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.service.mock.MockPresets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Stands in for Twitch's OAuth page under the mock profile: lists quick-launch accounts and a
 * form building a custom one, each of which goes on to {@code /auth/callback} with a mock code.
 * With the quick-login profile too, logging in skips that page for the first quick account;
 * {@code /mock/login} still switches account, from the member's config popout (mock-popout.js).
 * Outside the mock profile, nginx routes
 * {@code /oauth2/**} to catapult-api instead.
 */
@Controller
@Profile("mock")
@RequiredArgsConstructor
public class MockAuthController {

    public static final String QUICK_LOGIN_PROFILE = "quick-login";

    /** A quick-launch account: the code it logs in with, and what it showcases. */
    record QuickLogin(String jwt, String features, String code) {
        QuickLogin(String code, String features) {
            this(code, features, code);
        }
    }

    static final List<QuickLogin> QUICK_LOGINS = List.of(
            new QuickLogin("0", "Multiple account"),
            new QuickLogin("1", "Online account"),
            new QuickLogin("2", "Offline account")
    );

    private final Environment environment;

    @GetMapping("/oauth2/authorization/twitch")
    public String authorize(Model model) {
        if (environment.matchesProfiles(QUICK_LOGIN_PROFILE)) {
            return "redirect:/auth/callback?code=" + QUICK_LOGINS.getFirst().code();
        }
        return loginPage(model);
    }

    /** The account picker, reachable even when logging in skips it (the popout's "switch account"). */
    @GetMapping("/mock/login")
    public String loginPage(Model model) {
        model.addAttribute("jwts", QUICK_LOGINS);
        model.addAttribute("availableCcls", MockPresets.AVAILABLE_CCLS);
        model.addAttribute("availableTws", MockPresets.AVAILABLE_TWS);
        model.addAttribute("bindingStatuses", MockPresets.BINDING_STATUSES);
        model.addAttribute("sourceTypes", MockPresets.SOURCE_TYPES);
        model.addAttribute("minecraftStatuses", MockPresets.MINECRAFT_STATUSES);
        return "mock/jwt-select";
    }
}
