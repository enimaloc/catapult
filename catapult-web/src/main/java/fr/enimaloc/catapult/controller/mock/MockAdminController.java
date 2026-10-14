package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import fr.enimaloc.catapult.common.dto.twitchat.TwitchatEvents;
import fr.enimaloc.catapult.event.ChannelLiveStateEvent;
import fr.enimaloc.catapult.event.NavChannelsChangedEvent;
import fr.enimaloc.catapult.event.binding.GameChangedEvent;
import fr.enimaloc.catapult.event.minecraft.MinecraftSyncEvent;
import fr.enimaloc.catapult.event.steam.SteamConnectionStateEvent;
import fr.enimaloc.catapult.service.mock.MockApiService;
import fr.enimaloc.catapult.service.mock.MockData;
import fr.enimaloc.catapult.service.mock.MockPresets;
import jakarta.servlet.http.HttpSession;
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
import java.util.UUID;

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
        private boolean admin;
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
        private List<ModeratedForm> moderated = new ArrayList<>();
    }

    /** One channel the member moderates; {@code delete} drops it. */
    @Data
    public static class ModeratedForm {
        private UUID id;
        private String twitchId;
        private String twitchUsername;
        private String avatarUrl;
        private boolean live;
        private boolean delete;

        ChannelDto toDto() {
            return new ChannelDto(id, twitchId, twitchUsername, avatarUrl, live);
        }
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
        model.addAttribute("admin", data.isAdmin());
        model.addAttribute("game", data.getGameDto());
        model.addAttribute("bindings", data.getBinding());
        model.addAttribute("moderated", data.getModeratedChannels());
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

    /**
     * The part of the session no event tells an open page about: when it changes, the page has to
     * re-render to show it (the popout has the app do so, see mock-popout.js).
     */
    private record Unpushed(String avatarUrl, List<BindingDto> bindings, boolean hasSteamProvider,
                            boolean hasSteamPersonalToken, boolean steamTokenShared, long steamProfileCacheTtlMinutes,
                            boolean hasXboxProvider, boolean hasXbox, boolean hasMinecraftProvider) {
        static Unpushed of(MockData data) {
            return new Unpushed(data.getChannelDto().profileImageUrl(), List.copyOf(data.getBinding()),
                    data.isHasSteamProvider(), data.isHasSteamPersonalToken(), data.isSteamTokenShared(),
                    data.getSteamProfileCacheTtlMinutes(), data.isHasXboxProvider(), data.isHasXbox(),
                    data.isHasMinecraftProvider());
        }
    }

    /**
     * Applies the form; {@code addBinding} / {@code addModerated} (its "add" buttons) then append a
     * blank binding / moderated channel. Flags {@code appStale} when something changed that no event
     * carries to the app, {@code appReload} when the navbar itself has to be rendered again (the admin
     * menu); a change of the navbar's channels is pushed to it like catapult-api would.
     */
    @PostMapping("/mock/admin")
    public String apply(@ModelAttribute AdminForm form, @RequestParam(defaultValue = "false") boolean addBinding,
                        @RequestParam(defaultValue = "false") boolean addModerated, HttpSession session,
                        RedirectAttributes redirect) {
        MockData data = mockApiService.getData();
        if (data == null) {
            return REDIRECT_LOGIN;
        }
        Unpushed before = Unpushed.of(data);
        var navBefore = data.getChannelList();
        String username = data.getChannelDto().twitchUsername();

        data.setBindings(form.getBindings().stream().filter(b -> !b.isDelete()).map(BindingForm::toDto).toList());
        List<ChannelDto> moderated = form.getModerated().stream().filter(c -> !c.isDelete()).map(ModeratedForm::toDto).toList();
        publishLiveChanges(data.getModeratedChannels(), moderated);
        data.setModeratedChannels(moderated);

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
        if (data.isAdmin() != form.isAdmin()) {
            data.setAdmin(form.isAdmin());
            redirect.addFlashAttribute("appReload", true);
        }

        GameDto previous = data.getGameDto();
        if (previous == null || !previous.sourceName().equals(form.getDetectedSourceName())
                || !previous.sourceType().equals(form.getDetectedSourceType())) {
            Optional<BindingDto> previousBinding = previous == null ? Optional.empty()
                    : bindingOf(data, previous.sourceType(), previous.sourceName());
            Optional<BindingDto> current = bindingOf(data, form.getDetectedSourceType(), form.getDetectedSourceName());
            String bindingId = current.map(BindingDto::id).orElse(null);
            data.setDetectedGame(bindingId, form.getDetectedSourceType(), form.getDetectedSourceName());
            eventPublisher.publishEvent(new GameChangedEvent(username, bindingId,
                    form.getDetectedSourceType(), form.getDetectedSourceName()));
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
        if (addModerated) {
            data.addModeratedChannel();
        }
        if (!before.equals(Unpushed.of(data))) {
            redirect.addFlashAttribute("appStale", true);
        }
        if (!sameChannelsBut(navBefore.channels(), data.getChannelList().channels())) {
            eventPublisher.publishEvent(new NavChannelsChangedEvent(session.getId()));
        }
        return REDIRECT_ADMIN;
    }

    /** A moderated channel kept but switched live/offline goes out as that channel's live event. */
    private void publishLiveChanges(List<ChannelDto> before, List<ChannelDto> after) {
        for (ChannelDto channel : after) {
            before.stream().filter(c -> c.id().equals(channel.id()) && c.live() != channel.live()).findFirst()
                    .ifPresent(c -> eventPublisher.publishEvent(new ChannelLiveStateEvent(channel.twitchUsername(), channel.live())));
        }
    }

    /** Whether two navbar lists differ only by live states, which their own events carry. */
    private static boolean sameChannelsBut(List<ChannelDto> before, List<ChannelDto> after) {
        return offline(before).equals(offline(after));
    }

    private static List<ChannelDto> offline(List<ChannelDto> channels) {
        return channels.stream()
                .map(c -> new ChannelDto(c.id(), c.twitchId(), c.twitchUsername(), c.profileImageUrl(), false)).toList();
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
