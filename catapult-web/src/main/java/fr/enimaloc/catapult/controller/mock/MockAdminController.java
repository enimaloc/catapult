package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEvents;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.service.mock.MockApiService;
import fr.enimaloc.catapult.service.mock.MockData;
import fr.enimaloc.catapult.service.mock.MockPresets;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Mock-only admin page — the member's config, opened in a popout next to the app — for state
 * that the app's own UI never exposes a way to change: things only ever set once by the login
 * form (live status, providers, Steam token and diagnostics, the Minecraft link, the detected
 * game, bindings' status) or, since the UI has no "add binding" action, unreachable once deleted.
 * It also stands in for catapult-api's Twitchat dispatcher: changes that would make Catapult
 * send an event's payloads (stream start, game change, bot toggle) send them, and any event
 * can be sent by hand.
 */
@Controller
@RequiredArgsConstructor
@Profile("mock")
public class MockAdminController {
    private static final String REDIRECT_LOGIN = "redirect:/oauth2/authorization/twitch";
    private static final String REDIRECT_ADMIN = "redirect:/mock/admin";

    private final MockApiService mockApiService;
    private final ApplicationEventPublisher eventPublisher;

    /** The admin form: every field, a binding row per binding (indexed: bindings[0].sourceName…). */
    @Data
    public static class AdminForm {
        private boolean live;
        private String avatarUrl;
        private boolean botEnabled;
        private String detectedSourceType;
        private String detectedSourceName;
        private boolean hasSteamProvider;
        private boolean hasSteam;
        private boolean hasSteamPersonalToken;
        private boolean steamTokenShared;
        private boolean steamProfilePrivate;
        private boolean steamRateLimited;
        private boolean steamOfflineMode;
        private long steamProfileCacheTtlMinutes = 15;
        private boolean hasXboxProvider;
        private boolean hasXbox;
        private boolean hasMinecraftProvider;
        private String minecraftStatus = "NONE";
        private String minecraftName;
        private String minecraftServiceAccount;
        private List<BindingForm> bindings = new ArrayList<>();
    }

    /** One binding row; {@code delete} drops it. */
    @Data
    public static class BindingForm {
        private String id;
        private boolean delete;
        private String status;
        private String sourceType;
        private String sourceName;
        private String twitchGameId;
        private String twitchGameName;
        private boolean ignored;
        private boolean cclEnabled;
        private boolean twEnabled;
        private boolean twOverride;
        private Set<String> ccls = new HashSet<>();
        private Set<String> tws = new HashSet<>();

        BindingDto toDto() {
            return new BindingDto(id, status, sourceType, sourceName, twitchGameId, twitchGameName, ignored, cclEnabled,
                    Set.copyOf(ccls), twEnabled, twOverride, Set.copyOf(tws));
        }
    }

    @GetMapping("/mock/admin")
    public String edit(Model model) {
        MockData data = mockApiService.getData();
        if (data == null) {
            return REDIRECT_LOGIN;
        }
        LinkStateResponse minecraft = data.getMinecraftLink();
        model.addAttribute("channel", data.getChannelDto());
        model.addAttribute("botEnabled", data.isBotEnabled());
        model.addAttribute("game", data.getGameDto());
        model.addAttribute("bindings", data.getBinding());
        model.addAttribute("hasSteamProvider", data.isHasSteamProvider());
        model.addAttribute("hasSteam", data.isHasSteam());
        model.addAttribute("hasSteamPersonalToken", data.isHasSteamPersonalToken());
        model.addAttribute("steamTokenShared", data.isSteamTokenShared());
        model.addAttribute("hasXboxProvider", data.isHasXboxProvider());
        model.addAttribute("hasXbox", data.isHasXbox());
        model.addAttribute("steamProfilePrivate", data.isSteamProfilePrivate());
        model.addAttribute("steamRateLimited", data.isSteamRateLimited());
        model.addAttribute("steamOfflineMode", data.isSteamOfflineMode());
        model.addAttribute("steamProfileCacheTtlMinutes", data.getSteamProfileCacheTtlMinutes());
        model.addAttribute("hasMinecraftProvider", data.isHasMinecraftProvider());
        model.addAttribute("minecraft", minecraft == null ? new LinkStateResponse("NONE", null, null) : minecraft);
        model.addAttribute("twitchatEvents", twitchatEvents(data));
        model.addAttribute("sourceTypes", MockPresets.SOURCE_TYPES);
        model.addAttribute("bindingStatuses", MockPresets.BINDING_STATUSES);
        model.addAttribute("minecraftStatuses", MockPresets.MINECRAFT_STATUSES);
        model.addAttribute("availableCcls", MockPresets.AVAILABLE_CCLS);
        model.addAttribute("availableTws", MockPresets.AVAILABLE_TWS);
        return "mock/admin";
    }

    /** Each Catapult event with how many enabled payloads it would send. */
    private static Map<String, Long> twitchatEvents(MockData data) {
        Map<String, Long> events = new LinkedHashMap<>();
        for (String event : TwitchatEvents.ALL) {
            events.put(event, data.getTwitchatEvents().getOrDefault(event, List.of()).stream().filter(p -> p.enabled()).count());
        }
        return events;
    }

    /** Applies the form; {@code addBinding} (its "add a binding" button) then appends a blank binding. */
    @PostMapping("/mock/admin")
    public String apply(@ModelAttribute AdminForm form, @RequestParam(defaultValue = "false") boolean addBinding) {
        MockData data = mockApiService.getData();
        if (data == null) {
            return REDIRECT_LOGIN;
        }
        String username = data.getChannelDto().twitchUsername();

        data.setBindings(form.getBindings().stream().filter(b -> !b.isDelete()).map(BindingForm::toDto).toList());

        if (data.getChannelDto().live() != form.isLive()) {
            data.setChannelLive(form.isLive());
            eventPublisher.publishEvent(new ChannelLiveStateEvent(username, form.isLive()));
            if (form.isLive()) {
                mockApiService.dispatchTwitchatEvent("STREAM_STARTED", Map.of());
            }
        }
        data.setChannelAvatarUrl(form.getAvatarUrl());
        if (data.isBotEnabled() != form.isBotEnabled()) {
            mockApiService.toggleBot(username); // and its Twitchat event
        }

        GameDto previous = data.getGameDto();
        if (previous == null || !previous.sourceName().equals(form.getDetectedSourceName())
                || !previous.sourceType().equals(form.getDetectedSourceType())) {
            Optional<BindingDto> previousBinding = previous == null ? Optional.empty()
                    : bindingOf(data, previous.sourceType(), previous.sourceName());
            Optional<BindingDto> current = bindingOf(data, form.getDetectedSourceType(), form.getDetectedSourceName());
            data.setDetectedGame(form.getDetectedSourceType(), form.getDetectedSourceName());
            eventPublisher.publishEvent(new GameChangedEvent(username, current.map(BindingDto::id).orElse(null),
                    current.map(BindingDto::sourceType).orElse(null), current.map(BindingDto::sourceName).orElse(null)));
            dispatchGameChange(data, current, previousBinding.map(BindingDto::twitchGameName)
                    .orElse(previous == null ? "" : previous.sourceName()));
        }

        if (data.isHasSteam() != form.isHasSteam()
                || data.isSteamRateLimited() != form.isSteamRateLimited()
                || data.isSteamOfflineMode() != form.isSteamOfflineMode()
                || data.isSteamProfilePrivate() != form.isSteamProfilePrivate()) {
            eventPublisher.publishEvent(new SteamConnectionStateEvent(username, form.isHasSteam(), form.isSteamRateLimited(),
                    form.isSteamOfflineMode(), form.isSteamProfilePrivate()));
        }
        data.setSteamDiagnostics(form.isSteamProfilePrivate(), form.isSteamRateLimited(), form.isSteamOfflineMode(),
                form.getSteamProfileCacheTtlMinutes());
        data.setSteamPersonalToken(form.isHasSteamPersonalToken(), form.isSteamTokenShared());
        data.setProviders(form.isHasSteamProvider(), form.isHasSteam(), form.isHasXboxProvider(), form.isHasXbox());

        LinkStateResponse link = data.getMinecraftLink();
        data.setMinecraft(form.isHasMinecraftProvider(), form.getMinecraftStatus(), form.getMinecraftName(),
                form.getMinecraftServiceAccount());
        if (!Objects.equals(link, data.getMinecraftLink())) {
            eventPublisher.publishEvent(new MinecraftSyncEvent(username, data.getMinecraftLink().status(),
                    data.getMinecraftLink().minecraftName()));
        }
        if (addBinding) {
            data.addBinding();
        }
        return REDIRECT_ADMIN;
    }

    /**
     * What Catapult would tell Twitchat of the new detected game: its binding's category when it
     * has one (and the bot runs, else Catapult changes nothing), "no game found" otherwise.
     */
    private void dispatchGameChange(MockData data, Optional<BindingDto> binding, String previousGameName) {
        Optional<BindingDto> categorized = binding.filter(b -> !b.ignored() && b.twitchGameName() != null && !b.twitchGameName().isBlank());
        if (categorized.isEmpty()) {
            mockApiService.dispatchTwitchatEvent("NO_GAME_DETECTED", Map.of());
        } else if (data.isBotEnabled()) {
            mockApiService.dispatchTwitchatEvent("CATEGORY_CHANGED_BY_CATAPULT", Map.of(
                    "gameName", categorized.get().twitchGameName(), "previousGameName", Objects.requireNonNullElse(previousGameName, "")));
        }
    }

    private static Optional<BindingDto> bindingOf(MockData data, String sourceType, String sourceName) {
        return data.getBinding().stream()
                .filter(b -> b.sourceName().equalsIgnoreCase(sourceName) && b.sourceType().equalsIgnoreCase(sourceType))
                .findFirst();
    }

    /** Sends an event's payloads by hand, the game names standing in for the ones Catapult would fill. */
    @PostMapping("/mock/admin/twitchat")
    public String sendTwitchatEvent(@RequestParam String event, @RequestParam(defaultValue = "") String gameName,
                                    @RequestParam(defaultValue = "") String previousGameName, RedirectAttributes redirect) {
        if (mockApiService.getData() == null) {
            return REDIRECT_LOGIN;
        }
        if (!TwitchatEvents.ALL.contains(event)) {
            redirect.addFlashAttribute("twitchatResult", "Événement inconnu : " + event);
            return REDIRECT_ADMIN;
        }
        int sent = mockApiService.dispatchTwitchatEvent(event, Map.of("gameName", gameName, "previousGameName", previousGameName));
        redirect.addFlashAttribute("twitchatResult", sent == 0
                ? "Rien envoyé : aucun payload actif pour cet événement."
                : sent + " payload(s) envoyé(s) à Twitchat.");
        return REDIRECT_ADMIN;
    }
}
