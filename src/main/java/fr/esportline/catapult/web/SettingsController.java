package fr.esportline.catapult.web;

import fr.esportline.catapult.domain.OAuthToken;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.GetterConfigRepository;
import fr.esportline.catapult.repository.OAuthTokenRepository;
import fr.esportline.catapult.repository.UserSettingsRepository;
import fr.esportline.catapult.security.CatapultOAuth2User;
import fr.esportline.catapult.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class SettingsController {

    private final OAuthTokenRepository oAuthTokenRepository;
    private final GetterConfigRepository getterConfigRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final AccountService accountService;
    private final ClientRegistrationRepository clientRegistrationRepository;

    @GetMapping("/settings")
    public String settings(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();

        boolean hasSteamProvider = clientRegistrationRepository.findByRegistrationId("steam") != null;
        boolean hasDiscordProvider = clientRegistrationRepository.findByRegistrationId("discord") != null;

        model.addAttribute("user", user);
        model.addAttribute("hasSteamProvider", hasSteamProvider);
        model.addAttribute("hasDiscordProvider", hasDiscordProvider);
        model.addAttribute("hasSteam", hasSteamProvider && oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.STEAM).isPresent());
        model.addAttribute("hasDiscord", hasDiscordProvider && oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.DISCORD).isPresent());
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
        return "redirect:/settings";
    }

    @PostMapping("/settings/delete-account")
    public String deleteAccount(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @RequestParam String confirmUsername) {
        UserAccount user = principal.getUserAccount();
        if (user.getTwitchUsername().equalsIgnoreCase(confirmUsername)) {
            accountService.initiateAccountDeletion(user);
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/cancel-deletion")
    public String cancelDeletion(@AuthenticationPrincipal CatapultOAuth2User principal) {
        accountService.cancelAccountDeletion(principal.getUserAccount());
        return "redirect:/settings";
    }

    @PostMapping("/settings/disconnect")
    public String disconnectProvider(@AuthenticationPrincipal CatapultOAuth2User principal,
                                     @RequestParam String provider) {
        OAuthToken.Provider p = OAuthToken.Provider.valueOf(provider.toUpperCase());
        accountService.disconnectProvider(principal.getUserAccount(), p);
        return "redirect:/settings";
    }
}
