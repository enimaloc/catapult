package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@Profile("mock-steam")
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMembersMockController {

    private final UserAccountRepository userAccountRepository;
    private final MockTwitchEventSubService mockTwitchEventSubService;
    private final MockSteamApiClient mockSteamApiClient;
    private final IgdbService igdbService;

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

    @GetMapping(value = "/igdb/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, String>> searchIgdbGames(@RequestParam String q) {
        if (q.isBlank() || q.strip().length() < 2) return List.of();
        return igdbService.searchGames(q).stream()
                .map(g -> Map.of("id", g.id(), "name", g.name()))
                .toList();
    }
}
