package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.service.mock.MockData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Stands in for Twitch's OAuth page under the mock profile: lists quick-launch accounts and a
 * form building a custom one, each of which goes on to {@code /auth/callback} with a mock code.
 * Outside the mock profile, nginx routes {@code /oauth2/**} to catapult-api instead.
 */
@Controller
@Profile("mock")
public class MockAuthController {

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

    @GetMapping("/oauth2/authorization/twitch")
    public String loginPage(Model model) {
        model.addAttribute("jwts", QUICK_LOGINS);
        model.addAttribute("availableCcls", MockData.AVAILABLE_CCLS);
        model.addAttribute("availableTws", MockData.AVAILABLE_TWS);
        model.addAttribute("bindingStatuses", MockData.BINDING_STATUSES);
        model.addAttribute("sourceTypes", MockData.SOURCE_TYPES);
        model.addAttribute("minecraftStatuses", MockData.MINECRAFT_STATUSES);
        return "mock/jwt-select";
    }
}
