package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.getter.MockSteamApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@Profile("mock")
@RequiredArgsConstructor
@RequestMapping("/mock/steam")
public class MockSteamController {

    private final MockSteamApiClient mockClient;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("globalGame", mockClient.getGlobalGame().orElse(null));
        model.addAttribute("gameByUser", mockClient.getGameByUser());
        return "mock/steam";
    }

    @PostMapping("/global/set")
    public String setGlobalGame(@RequestParam String gameId, @RequestParam String gameName) {
        mockClient.setGlobalGame(gameId.strip(), gameName.strip());
        return "redirect:/mock/steam";
    }

    @PostMapping("/global/clear")
    public String clearGlobalGame() {
        mockClient.clearGlobalGame();
        return "redirect:/mock/steam";
    }

    @PostMapping("/user/set")
    public String setGameForUser(@RequestParam String steamId,
                                  @RequestParam String gameId,
                                  @RequestParam String gameName) {
        mockClient.setGameForUser(steamId.strip(), gameId.strip(), gameName.strip());
        return "redirect:/mock/steam";
    }

    @PostMapping("/user/clear")
    public String clearGameForUser(@RequestParam String steamId) {
        mockClient.clearGameForUser(steamId.strip());
        return "redirect:/mock/steam";
    }
}
