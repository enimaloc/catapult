package fr.esportline.catapult.web;

import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.getter.DetectedGame;
import fr.esportline.catapult.security.CatapultOAuth2User;
import fr.esportline.catapult.service.GameStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final GameStateService gameStateService;

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();
        Optional<DetectedGame> currentGame = gameStateService.getLastKnownGame(user);

        model.addAttribute("user", user);
        model.addAttribute("currentGame", currentGame.orElse(null));
        model.addAttribute("botEnabled", user.isBotEnabled());
        model.addAttribute("isPendingDeletion", user.getStatus() == UserAccount.Status.PENDING_DELETION);

        return "dashboard";
    }
}
