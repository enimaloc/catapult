package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Controller
@Profile("mock")
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMembersMockController {

    private final UserAccountRepository userAccountRepository;
    private final MockTwitchEventSubService mockTwitchEventSubService;
    private final MockSteamApiClient mockSteamApiClient;

    @PostMapping("/{id}/twitch/online")
    public String setTwitchOnline(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockTwitchEventSubService.setOnline(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/twitch/offline")
    public String setTwitchOffline(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockTwitchEventSubService.setOffline(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/set")
    public String setSteamGame(@PathVariable UUID id,
                                @RequestParam String gameId,
                                @RequestParam String gameName) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no Steam ID");
        }
        mockSteamApiClient.setGameForUser(user.getSteamId(), gameId.strip(), gameName.strip());
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/clear")
    public String clearSteamGame(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no Steam ID");
        }
        mockSteamApiClient.clearGameForUser(user.getSteamId());
        return "redirect:/admin/members";
    }
}
