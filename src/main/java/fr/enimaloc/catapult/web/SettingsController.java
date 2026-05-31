package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class SettingsController {

    private static final String REDIRECT_SETTINGS = "redirect:/settings";

    @Value("${steam.api-key:}")
    private String steamApiKey;

    private final OAuthTokenRepository oAuthTokenRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final AccountService accountService;

    public static final String REDIRECT_SETTINGS = "redirect:/settings";

    @GetMapping("/settings")
    public String settings(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();

        model.addAttribute("user", user);
        model.addAttribute("hasSteamProvider", !steamApiKey.isBlank());
        model.addAttribute("hasSteam", !steamApiKey.isBlank() && user.getSteamId() != null);
        model.addAttribute("getterConfigs", getterConfigRepository.findByUserOrderByPriorityAsc(user));
        model.addAttribute("settings", userSettingsRepository.findById(user.getId()).orElse(null));
        model.addAttribute("isPendingDeletion", user.getStatus() == UserAccount.Status.PENDING_DELETION);

        return "settings";
    }

    @PostMapping("/settings/bot")
    public String toggleBot(@AuthenticationPrincipal CatapultOAuth2User principal,
                            @RequestParam boolean enabled) {
        UserAccount user = principal.getUserAccount();
        user.setBotEnabled(enabled);
        // Save via AccountService or UserAccountRepository
        return REDIRECT_SETTINGS;
    }

    @PostMapping("/settings/delete-account")
    public String deleteAccount(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @RequestParam String confirmUsername) {
        UserAccount user = principal.getUserAccount();
        if (user.getTwitchUsername().equalsIgnoreCase(confirmUsername)) {
            accountService.initiateAccountDeletion(user);
        }
        return REDIRECT_SETTINGS;
    }

    @PostMapping("/settings/cancel-deletion")
    public String cancelDeletion(@AuthenticationPrincipal CatapultOAuth2User principal) {
        accountService.cancelAccountDeletion(principal.getUserAccount());
        return REDIRECT_SETTINGS;
    }

    @PostMapping("/settings/disconnect")
    public String disconnectProvider(@AuthenticationPrincipal CatapultOAuth2User principal,
                                     @RequestParam String provider) {
        OAuthToken.Provider p = OAuthToken.Provider.valueOf(provider.toUpperCase());
        accountService.disconnectProvider(principal.getUserAccount(), p);
        return REDIRECT_SETTINGS;
    }
}
