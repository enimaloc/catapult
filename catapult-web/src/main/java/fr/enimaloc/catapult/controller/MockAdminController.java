package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.mock.MockApiService;
import fr.enimaloc.catapult.service.mock.MockData;
import fr.enimaloc.catapult.ws.event.ChannelUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * Mock-only admin page for state that the app's own UI never exposes a way to change —
 * things only ever set once by the login form (live status, provider availability, Steam
 * diagnostics, the currently detected game, a binding's status) or, since the UI has no
 * "add binding" action, unreachable at all once deleted.
 */
@Controller
@RequiredArgsConstructor
@Profile("mock")
public class MockAdminController {
    private final MockApiService mockApiService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping("/mock/admin")
    public String edit(Model model) {
        MockData data = mockApiService.getData();
        if (data == null) {
            return "redirect:/oauth2/authorization/twitch";
        }

        model.addAttribute("channel", data.getChannelDto());
        model.addAttribute("game", data.getGameDto());
        model.addAttribute("binding", data.getBinding());
        model.addAttribute("hasSteamProvider", data.isHasSteamProvider());
        model.addAttribute("hasSteam", data.isHasSteam());
        model.addAttribute("hasXboxProvider", data.isHasXboxProvider());
        model.addAttribute("hasXbox", data.isHasXbox());
        model.addAttribute("steamProfilePrivate", data.isSteamProfilePrivate());
        model.addAttribute("steamRateLimited", data.isSteamRateLimited());
        model.addAttribute("steamOfflineMode", data.isSteamOfflineMode());
        model.addAttribute("steamProfileCacheTtlMinutes", data.getSteamProfileCacheTtlMinutes());
        model.addAttribute("sourceTypes", List.of("STEAM", "XBOX", "MINECRAFT"));
        model.addAttribute("bindingStatuses", List.of("AUTO", "MANUAL", "INCOMPLETE"));
        model.addAttribute("availableCcls", MockData.AVAILABLE_CCLS);
        model.addAttribute("availableTws", MockData.AVAILABLE_TWS);

        return "mock/admin";
    }

    @PostMapping("/mock/admin")
    public String apply(
            @RequestParam(defaultValue = "false") boolean live,
            @RequestParam String avatarUrl,
            @RequestParam String detectedSourceType,
            @RequestParam String detectedSourceName,
            @RequestParam(defaultValue = "false") boolean hasSteamProvider,
            @RequestParam(defaultValue = "false") boolean hasSteam,
            @RequestParam(defaultValue = "false") boolean hasXboxProvider,
            @RequestParam(defaultValue = "false") boolean hasXbox,
            @RequestParam(defaultValue = "false") boolean steamProfilePrivate,
            @RequestParam(defaultValue = "false") boolean steamRateLimited,
            @RequestParam(defaultValue = "false") boolean steamOfflineMode,
            @RequestParam long steamProfileCacheTtlMinutes,
            @RequestParam String bindingStatus,
            @RequestParam String bindingSourceType,
            @RequestParam String bindingSourceName,
            @RequestParam String bindingTwitchGameId,
            @RequestParam String bindingTwitchGameName,
            @RequestParam(defaultValue = "false") boolean bindingIgnored,
            @RequestParam(defaultValue = "false") boolean bindingCclEnabled,
            @RequestParam(defaultValue = "false") boolean bindingTwEnabled,
            @RequestParam(defaultValue = "false") boolean bindingTwOverride,
            @RequestParam(required = false) Set<String> bindingCcls,
            @RequestParam(required = false) Set<String> bindingTws
    ) {
        MockData data = mockApiService.getData();
        if (data == null) {
            return "redirect:/oauth2/authorization/twitch";
        }

        data.setChannelLive(live);
        data.setChannelAvatarUrl(avatarUrl);
        data.setDetectedGame(detectedSourceType, detectedSourceName);
        data.setProviders(hasSteamProvider, hasSteam, hasXboxProvider, hasXbox);
        data.setSteamDiagnostics(steamProfilePrivate, steamRateLimited, steamOfflineMode, steamProfileCacheTtlMinutes);
        data.replaceBinding(bindingStatus, bindingSourceType, bindingSourceName, bindingTwitchGameId, bindingTwitchGameName,
                bindingIgnored, bindingCclEnabled, bindingCcls == null ? Set.of() : bindingCcls,
                bindingTwEnabled, bindingTwOverride, bindingTws == null ? Set.of() : bindingTws);

        eventPublisher.publishEvent(new ChannelUpdatedEvent(data.getChannelDto().twitchUsername()));

        return "redirect:/mock/admin";
    }
}
