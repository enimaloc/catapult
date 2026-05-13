package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import fr.enimaloc.catapult.service.StreamStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@Profile("mock")
@RequiredArgsConstructor
@RequestMapping("/mock/twitch")
public class MockTwitchController {

    private final MockTwitchEventSubService mockService;
    private final StreamStateService streamStateService;

    @GetMapping
    public String page(Model model) {
        Map<UserAccount, Boolean> usersLiveStatus = new LinkedHashMap<>();
        for (UserAccount user : mockService.getAllUsers()) {
            usersLiveStatus.put(user, streamStateService.isLive(user));
        }
        model.addAttribute("usersLiveStatus", usersLiveStatus);
        return "mock/twitch";
    }

    @PostMapping("/online")
    public String setOnline(@RequestParam UUID userId) {
        mockService.getAllUsers().stream()
            .filter(u -> u.getId().equals(userId))
            .findFirst()
            .ifPresent(mockService::setOnline);
        return "redirect:/mock/twitch";
    }

    @PostMapping("/offline")
    public String setOffline(@RequestParam UUID userId) {
        mockService.getAllUsers().stream()
            .filter(u -> u.getId().equals(userId))
            .findFirst()
            .ifPresent(mockService::setOffline);
        return "redirect:/mock/twitch";
    }
}
