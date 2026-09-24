package fr.enimaloc.catapult.service.mock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.common.dto.*;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private final ChannelDto channelDto;
    private final GameDto gameDto;
    private final BindingDto bindingDto;
    private final UserSettingsDto userSettingsDto;
    private final List<ChannelDto> channelsList;
    private final boolean hasSteamProvider;
    private final boolean hasSteam;
    private final boolean hasSteamPersonalToken;
    private final boolean steamTokenShared;
    private final boolean steamProfilePrivate;
    private final boolean steamRateLimited;
    private final boolean steamOfflineMode;
    private final boolean hasXboxProvider;
    private final boolean hasXbox;
    private final LinkStateResponse minecraftLink;
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
        this.bindingDto = bindingDto;
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

    public ChannelListResponse getChannelList() {
        return new ChannelListResponse(channelDto.twitchId(), channelsList);
    }

    public ChannelPageData getPage(String username, String status, String source) {
        return new ChannelPageData(
                getChannelUser(), username, username.equals(channelDto.twitchUsername()),
                channelDto.live(), botEnabled, gameDto,
                new PagedBindings(0, 1, 1, List.of(bindingDto)),
                AVAILABLE_CCLS, userSettingsDto.blockedCcls(), AVAILABLE_TWS, userSettingsDto.blockedTws(),
                status, source, hasSteamProvider, hasSteam, hasSteamPersonalToken, steamTokenShared,
                steamProfilePrivate, steamRateLimited, steamOfflineMode, 15L,
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
