package fr.enimaloc.catapult.service.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.common.dto.*;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class MockData {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Fixed catalog the mock login form picks blocked entries from — not itself customizable. */
    public static final List<CclDto> AVAILABLE_CCLS = List.of(
            new CclDto("violent-graphic", "Violence graphique"),
            new CclDto("gambling", "Jeux d'argent simulés"),
            new CclDto("nudity", "Thèmes sexuels")
    );
    public static final List<TwDto> AVAILABLE_TWS = List.of(
            new TwDto("jumpscares", "Jumpscares"),
            new TwDto("flashing-lights", "Lumières stroboscopiques"),
            new TwDto("loud-noises", "Bruits soudains")
    );

    public static final ChannelDto[] CHANNEL_DTOS = {
            new ChannelDto(
                    UUID.nameUUIDFromBytes("online".getBytes(StandardCharsets.UTF_8)),
                    "00000001",
                    "enimaloc_stream",
                    "https://static-cdn.jtvnw.net/jtv_user_pictures/a69d9843-4cd4-4b3a-815d-0101b31e4790-profile_image-70x70.png",
                    true
            ),
            new ChannelDto(
                    UUID.nameUUIDFromBytes("offline".getBytes(StandardCharsets.UTF_8)),
                    "00000002",
                    "enimaloc",
                    "https://static-cdn.jtvnw.net/jtv_user_pictures/a69d9843-4cd4-4b3a-815d-0101b31e4790-profile_image-70x70.png",
                    false
            )
    };
    public static final BindingDto[] BINDING_DTOS = {
            new BindingDto(
                    UUID.nameUUIDFromBytes("STUNTBOOST".getBytes(StandardCharsets.UTF_8)).toString(),
                    "AUTO", "STEAM", "STUNTBOOST", "000001",
                    "Stuntboost", false, true, Set.of(), true,
                    false, Set.of())
    };
    public static final GameDto[] GAME_DTOS = new GameDto[BINDING_DTOS.length];
    public static final UserSettingsDto[] SETTINGS_DTOS = {
            new UserSettingsDto(
                    true, Set.of("violent-graphic"),
                    null, null, Set.of(), false,
                    false, false, null,
                    null, Set.of(),
                    AVAILABLE_CCLS,
                    false, Set.of(),
                    AVAILABLE_TWS)
    };

    static {
        for (int i = 0; i < BINDING_DTOS.length; i++) {
            GAME_DTOS[i] = new GameDto(BINDING_DTOS[i].sourceName(), BINDING_DTOS[i].sourceType());
        }
    }

    @Getter
    private ChannelDto channelDto;
    @Getter
    private GameDto gameDto;
    /** Nullable: cleared by {@link #deleteBinding}, matching a real "no binding" state. */
    @Getter
    private BindingDto binding;
    private UserSettingsDto userSettingsDto;
    private final List<ChannelDto> channelsList;
    @Getter
    private boolean hasSteamProvider;
    @Getter
    private boolean hasSteam;
    private boolean hasSteamPersonalToken;
    private boolean steamTokenShared;
    @Getter
    private boolean steamProfilePrivate;
    @Getter
    private boolean steamRateLimited;
    @Getter
    private boolean steamOfflineMode;
    /** Not reachable from the app's own UI — only {@link MockAdminController} edits this. */
    @Getter
    private long steamProfileCacheTtlMinutes = 15L;
    @Getter
    private boolean hasXboxProvider;
    @Getter
    private boolean hasXbox;
    private LinkStateResponse minecraftLink;
    @Setter @Getter
    private boolean botEnabled;

    /**
     * Parses {@code jwt} as either a full custom config — a JSON object built by the mock
     * login form ({@code jwt-select.html}'s inline script serializes every field into
     * {@code code} before submitting) — or, for the 3 quick-launch links, a legacy single-digit
     * index into the preset arrays above.
     */
    public static MockData fromJwt(String jwt) {
        if (jwt != null && jwt.startsWith("{")) {
            try {
                return fromConfig(JSON.readValue(jwt, MockConfig.class));
            } catch (Exception e) {
                // Malformed config (e.g. hand-edited URL) — fall back to the default preset
                // rather than 500ing the mock login flow.
                return fromJwt("0");
            }
        }
        int id = Integer.parseInt(jwt);
        return new MockData(
                CHANNEL_DTOS[Integer.min(id, CHANNEL_DTOS.length - 1)],
                GAME_DTOS[Integer.min(id, GAME_DTOS.length - 1)],
                BINDING_DTOS[Integer.min(id, BINDING_DTOS.length - 1)],
                SETTINGS_DTOS[Integer.min(id, SETTINGS_DTOS.length - 1)],
                id == 0 ? List.of(CHANNEL_DTOS) : List.of(),
                true, true, true, false, false, false, false,
                false, false,
                new LinkStateResponse("NONE", null, null));
    }

    private static MockData fromConfig(MockConfig c) {
        ChannelDto channel = new ChannelDto(
                UUID.nameUUIDFromBytes(c.username.getBytes(StandardCharsets.UTF_8)),
                c.twitchId, c.username, c.avatarUrl, c.live);

        BindingDto binding = new BindingDto(
                UUID.nameUUIDFromBytes(c.binding.sourceName.getBytes(StandardCharsets.UTF_8)).toString(),
                c.binding.status, c.binding.sourceType, c.binding.sourceName,
                c.binding.twitchGameId, c.binding.twitchGameName,
                c.binding.ignored, c.binding.cclEnabled, Set.copyOf(c.binding.ccls),
                c.binding.twEnabled, c.binding.twOverride, Set.copyOf(c.binding.tws));

        GameDto game = new GameDto(binding.sourceName(), binding.sourceType());

        UserSettingsDto settings = new UserSettingsDto(
                c.settings.cclFeatureEnabled, Set.copyOf(c.settings.blockedCcls),
                null, null, Set.of(), false, false, false,
                null, null, Set.of(),
                AVAILABLE_CCLS,
                c.settings.twFeatureEnabled, Set.copyOf(c.settings.blockedTws),
                AVAILABLE_TWS);

        LinkStateResponse minecraft = new LinkStateResponse(
                c.minecraft.status, c.minecraft.minecraftName, c.minecraft.serviceAccountUsername);

        MockData data = new MockData(channel, game, binding, settings, List.of(),
                c.steam.connected, c.steam.connected, c.steam.hasPersonalToken, c.steam.tokenShared,
                c.steam.profilePrivate, c.steam.rateLimited, c.steam.offlineMode,
                c.xbox.connected, c.xbox.connected, minecraft);
        data.setBotEnabled(c.botEnabled);
        return data;
    }

    public MockData(ChannelDto channelDto, GameDto gameDto, BindingDto bindingDto, UserSettingsDto userSettingsDto,
                     List<ChannelDto> channelsList,
                     boolean hasSteamProvider, boolean hasSteam, boolean hasSteamPersonalToken,
                     boolean steamTokenShared, boolean steamProfilePrivate, boolean steamRateLimited,
                     boolean steamOfflineMode, boolean hasXboxProvider, boolean hasXbox,
                     LinkStateResponse minecraftLink) {
        this.channelDto = channelDto;
        this.gameDto = gameDto;
        this.binding = bindingDto;
        this.userSettingsDto = userSettingsDto;
        if (!channelsList.contains(channelDto)) {
            channelsList = new ArrayList<>(channelsList);
            channelsList.addFirst(channelDto);
        }
        this.channelsList = channelsList;
        this.hasSteamProvider = hasSteamProvider;
        this.hasSteam = hasSteam;
        this.hasSteamPersonalToken = hasSteamPersonalToken;
        this.steamTokenShared = steamTokenShared;
        this.steamProfilePrivate = steamProfilePrivate;
        this.steamRateLimited = steamRateLimited;
        this.steamOfflineMode = steamOfflineMode;
        this.hasXboxProvider = hasXboxProvider;
        this.hasXbox = hasXbox;
        this.minecraftLink = minecraftLink;
        this.botEnabled = true;
    }

    private static final String[] RANDOM_NAME_PARTS = {
            "nova", "pixel", "comet", "ember", "quartz", "raven", "glitch", "vortex", "cinder", "haze"
    };
    private static final java.util.Random RANDOM = new Random();

    /**
     * Builds a fresh, randomly-populated channel — used for every mock connection that
     * doesn't hand a full JSON config to {@link #fromJwt}, so opening several mock sessions
     * at once (several browser tabs, or the digit quick-launch links) gets distinct-looking
     * data instead of colliding on the same fixed preset.
     */
    public static MockData random() {
        String username = RANDOM_NAME_PARTS[RANDOM.nextInt(RANDOM_NAME_PARTS.length)]
                + "_" + Integer.toHexString(RANDOM.nextInt(0x10000));
        ChannelDto channel = new ChannelDto(
                UUID.randomUUID(),
                String.valueOf(10_000_000 + RANDOM.nextInt(90_000_000)),
                username,
                CHANNEL_DTOS[0].profileImageUrl(),
                RANDOM.nextBoolean());

        MockData data = new MockData(channel, GAME_DTOS[0], BINDING_DTOS[0], SETTINGS_DTOS[0], List.of(),
                true, RANDOM.nextBoolean(), false, false, false, false, false,
                true, RANDOM.nextBoolean(),
                new LinkStateResponse("NONE", null, null));
        data.setBotEnabled(true);
        return data;
    }

    public ChannelListResponse getChannelList() {
        return new ChannelListResponse(channelDto.twitchId(), channelsList);
    }

    public ChannelPageData getPage(String username, String status, String source) {
        List<BindingDto> content = binding == null ? List.of() : List.of(binding);
        return new ChannelPageData(
                getChannelUser(), username, username.equals(channelDto.twitchUsername()),
                channelDto.live(), botEnabled, gameDto,
                new PagedBindings(0, content.isEmpty() ? 0 : 1, content.size(), content),
                AVAILABLE_CCLS, userSettingsDto.blockedCcls(), AVAILABLE_TWS, userSettingsDto.blockedTws(),
                status, source, hasSteamProvider, hasSteam, hasSteamPersonalToken, steamTokenShared,
                steamProfilePrivate, steamRateLimited, steamOfflineMode, steamProfileCacheTtlMinutes,
                hasXboxProvider, hasXbox, "00000000-0000-0000-0000-000000000000"
        );
    }

    public ChannelUserDto getChannelUser() {
        return new ChannelUserDto(channelDto.id().toString(), channelDto.twitchId(), channelDto.twitchUsername(), channelDto.profileImageUrl());
    }

    public UserSettingsDto getUserSettings() {
        return userSettingsDto;
    }

    public LinkStateResponse getMinecraftLink() {
        return minecraftLink;
    }

    private boolean matchesBinding(String bindingId) {
        return binding != null && binding.id().equals(bindingId);
    }

    public void setBindingCclEnabled(String bindingId, boolean enabled) {
        if (matchesBinding(bindingId)) {
            binding = new BindingDto(binding.id(), binding.status(), binding.sourceType(), binding.sourceName(),
                    binding.twitchGameId(), binding.twitchGameName(), binding.ignored(), enabled, binding.ccls(),
                    binding.twEnabled(), binding.twOverride(), binding.tws());
        }
    }

    public void setBindingIgnored(String bindingId, boolean ignored) {
        if (matchesBinding(bindingId)) {
            binding = new BindingDto(binding.id(), binding.status(), binding.sourceType(), binding.sourceName(),
                    binding.twitchGameId(), binding.twitchGameName(), ignored, binding.cclEnabled(), binding.ccls(),
                    binding.twEnabled(), binding.twOverride(), binding.tws());
        }
    }

    public void deleteBinding(String bindingId) {
        if (matchesBinding(bindingId)) {
            binding = null;
        }
    }

    public void updateBindingGame(String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        if (matchesBinding(bindingId)) {
            binding = new BindingDto(binding.id(), binding.status(), binding.sourceType(), binding.sourceName(),
                    twitchGameId, twitchGameName, binding.ignored(), binding.cclEnabled(), Set.copyOf(ccls),
                    binding.twEnabled(), binding.twOverride(), binding.tws());
        }
    }

    public void setBindingTws(String bindingId, Set<String> tws) {
        if (matchesBinding(bindingId)) {
            binding = new BindingDto(binding.id(), binding.status(), binding.sourceType(), binding.sourceName(),
                    binding.twitchGameId(), binding.twitchGameName(), binding.ignored(), binding.cclEnabled(),
                    binding.ccls(), binding.twEnabled(), true, Set.copyOf(tws));
        }
    }

    public void resetBindingTws(String bindingId) {
        if (matchesBinding(bindingId)) {
            binding = new BindingDto(binding.id(), binding.status(), binding.sourceType(), binding.sourceName(),
                    binding.twitchGameId(), binding.twitchGameName(), binding.ignored(), binding.cclEnabled(),
                    binding.ccls(), binding.twEnabled(), false, Set.of());
        }
    }

    public void setBindingTwEnabled(String bindingId, boolean enabled) {
        if (matchesBinding(bindingId)) {
            binding = new BindingDto(binding.id(), binding.status(), binding.sourceType(), binding.sourceName(),
                    binding.twitchGameId(), binding.twitchGameName(), binding.ignored(), binding.cclEnabled(),
                    binding.ccls(), enabled, binding.twOverride(), binding.tws());
        }
    }

    public void saveCclSettings(boolean enabled, Set<String> blockedCcls) {
        userSettingsDto = new UserSettingsDto(enabled, Set.copyOf(blockedCcls),
                userSettingsDto.noGameTwitchGameId(), userSettingsDto.noGameTwitchGameName(), userSettingsDto.noGameCcls(),
                userSettingsDto.applyDefaultOnStreamStart(), userSettingsDto.applyDefaultOnNoGame(), userSettingsDto.applyDefaultOnStreamEnd(),
                userSettingsDto.incompleteFallbackTwitchGameId(), userSettingsDto.incompleteFallbackTwitchGameName(), userSettingsDto.incompleteFallbackCcls(),
                userSettingsDto.availableCcls(), userSettingsDto.twFeatureEnabled(), userSettingsDto.blockedTws(), userSettingsDto.availableTws());
    }

    public void saveTwSettings(boolean enabled, Set<String> blockedTws) {
        userSettingsDto = new UserSettingsDto(userSettingsDto.cclFeatureEnabled(), userSettingsDto.blockedCcls(),
                userSettingsDto.noGameTwitchGameId(), userSettingsDto.noGameTwitchGameName(), userSettingsDto.noGameCcls(),
                userSettingsDto.applyDefaultOnStreamStart(), userSettingsDto.applyDefaultOnNoGame(), userSettingsDto.applyDefaultOnStreamEnd(),
                userSettingsDto.incompleteFallbackTwitchGameId(), userSettingsDto.incompleteFallbackTwitchGameName(), userSettingsDto.incompleteFallbackCcls(),
                userSettingsDto.availableCcls(), enabled, Set.copyOf(blockedTws), userSettingsDto.availableTws());
    }

    public void saveNoGameSettings(String twitchGameId, String twitchGameName, Set<String> ccls,
                                    boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        userSettingsDto = new UserSettingsDto(userSettingsDto.cclFeatureEnabled(), userSettingsDto.blockedCcls(),
                twitchGameId, twitchGameName, Set.copyOf(ccls),
                applyOnStreamStart, applyOnNoGame, applyOnStreamEnd,
                userSettingsDto.incompleteFallbackTwitchGameId(), userSettingsDto.incompleteFallbackTwitchGameName(), userSettingsDto.incompleteFallbackCcls(),
                userSettingsDto.availableCcls(), userSettingsDto.twFeatureEnabled(), userSettingsDto.blockedTws(), userSettingsDto.availableTws());
    }

    public void saveIncompleteFallbackSettings(String twitchGameId, String twitchGameName, Set<String> ccls) {
        userSettingsDto = new UserSettingsDto(userSettingsDto.cclFeatureEnabled(), userSettingsDto.blockedCcls(),
                userSettingsDto.noGameTwitchGameId(), userSettingsDto.noGameTwitchGameName(), userSettingsDto.noGameCcls(),
                userSettingsDto.applyDefaultOnStreamStart(), userSettingsDto.applyDefaultOnNoGame(), userSettingsDto.applyDefaultOnStreamEnd(),
                twitchGameId, twitchGameName, Set.copyOf(ccls),
                userSettingsDto.availableCcls(), userSettingsDto.twFeatureEnabled(), userSettingsDto.blockedTws(), userSettingsDto.availableTws());
    }

    public void minecraftEnroll(String name) {
        minecraftLink = new LinkStateResponse("PENDING", name, null);
    }

    /** Simulates the invite being accepted in-game the moment it's checked — the mock has no game client to poll. */
    public void minecraftSync() {
        if (minecraftLink != null
                && ("PENDING".equals(minecraftLink.status()) || "REMOVED".equals(minecraftLink.status()))) {
            minecraftLink = new LinkStateResponse("ACCEPTED", minecraftLink.minecraftName(), minecraftLink.serviceAccountUsername());
        }
    }

    public void minecraftDisconnect() {
        minecraftLink = new LinkStateResponse("NONE", null, null);
    }

    public void saveSteamToken(boolean shared) {
        hasSteamPersonalToken = true;
        steamTokenShared = shared;
    }

    public void setSteamTokenShared(boolean shared) {
        steamTokenShared = shared;
    }

    public void deleteSteamToken() {
        hasSteamPersonalToken = false;
        steamTokenShared = false;
    }

    /*
     * Admin-only mutators — none of these are reachable through the app's own UI, only
     * through MockAdminController's /mock/admin page.
     */

    public void setChannelLive(boolean live) {
        channelDto = new ChannelDto(channelDto.id(), channelDto.twitchId(), channelDto.twitchUsername(),
                channelDto.profileImageUrl(), live);
    }

    public void setChannelAvatarUrl(String avatarUrl) {
        channelDto = new ChannelDto(channelDto.id(), channelDto.twitchId(), channelDto.twitchUsername(),
                avatarUrl, channelDto.live());
    }

    /** The game Catapult currently detects running — independent of the binding's Twitch category mapping. */
    public void setDetectedGame(String sourceType, String sourceName) {
        gameDto = new GameDto(sourceName, sourceType);
    }

    public void setProviders(boolean hasSteamProvider, boolean hasSteam, boolean hasXboxProvider, boolean hasXbox) {
        this.hasSteamProvider = hasSteamProvider;
        this.hasSteam = hasSteam;
        this.hasXboxProvider = hasXboxProvider;
        this.hasXbox = hasXbox;
    }

    public void setSteamDiagnostics(boolean profilePrivate, boolean rateLimited, boolean offlineMode, long cacheTtlMinutes) {
        this.steamProfilePrivate = profilePrivate;
        this.steamRateLimited = rateLimited;
        this.steamOfflineMode = offlineMode;
        this.steamProfileCacheTtlMinutes = cacheTtlMinutes;
    }

    /**
     * Creates or fully replaces the single binding slot, including {@code status} — which the
     * app's own binding-edit panel never touches — and works even after {@link #deleteBinding}
     * cleared it, since the UI has no "add binding" action at all.
     */
    public void replaceBinding(String status, String sourceType, String sourceName, String twitchGameId,
                                String twitchGameName, boolean ignored, boolean cclEnabled, Set<String> ccls,
                                boolean twEnabled, boolean twOverride, Set<String> tws) {
        String id = binding != null
                ? binding.id()
                : UUID.nameUUIDFromBytes(sourceName.getBytes(StandardCharsets.UTF_8)).toString();
        binding = new BindingDto(id, status, sourceType, sourceName, twitchGameId, twitchGameName,
                ignored, cclEnabled, Set.copyOf(ccls), twEnabled, twOverride, Set.copyOf(tws));
    }

    /** Deserialization target for the mock login form's JSON payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MockConfig {
        public String username = "enimaloc_stream";
        public String twitchId = "00000001";
        public String avatarUrl = "https://static-cdn.jtvnw.net/jtv_user_pictures/a69d9843-4cd4-4b3a-815d-0101b31e4790-profile_image-70x70.png";
        public boolean live = true;
        public boolean botEnabled = true;
        public SteamConfig steam = new SteamConfig();
        public XboxConfig xbox = new XboxConfig();
        public MinecraftConfig minecraft = new MinecraftConfig();
        public BindingConfig binding = new BindingConfig();
        public SettingsConfig settings = new SettingsConfig();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SteamConfig {
        public boolean connected = true;
        public boolean hasPersonalToken = false;
        public boolean tokenShared = false;
        public boolean profilePrivate = false;
        public boolean rateLimited = false;
        public boolean offlineMode = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class XboxConfig {
        public boolean connected = false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MinecraftConfig {
        public String status = "NONE";
        public String minecraftName = null;
        public String serviceAccountUsername = null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BindingConfig {
        public String sourceType = "STEAM";
        public String sourceName = "STUNTBOOST";
        public String twitchGameId = "000001";
        public String twitchGameName = "Stuntboost";
        public String status = "AUTO";
        public boolean ignored = false;
        public boolean cclEnabled = true;
        public boolean twEnabled = true;
        public boolean twOverride = false;
        public List<String> ccls = List.of();
        public List<String> tws = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SettingsConfig {
        public boolean cclFeatureEnabled = true;
        public boolean twFeatureEnabled = false;
        public List<String> blockedCcls = List.of();
        public List<String> blockedTws = List.of();
    }
}
