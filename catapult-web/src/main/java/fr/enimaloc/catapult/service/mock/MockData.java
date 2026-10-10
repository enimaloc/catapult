package fr.enimaloc.catapult.service.mock;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelListResponse;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.common.dto.connect.LinkStateResponse;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static fr.enimaloc.catapult.service.mock.MockPresets.AVAILABLE_CCLS;
import static fr.enimaloc.catapult.service.mock.MockPresets.AVAILABLE_TWS;

/**
 * One mock session's state: what the real catapult-api would hold for the logged-in channel.
 * Built by the mock login flow ({@link #fromJwt}, {@link #random}) and mutated by
 * {@link MockApiService} as the dashboard sends actions, or by the mock admin page.
 */
public class MockData {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Random RANDOM = new Random();
    private static final String[] RANDOM_NAME_PARTS = {
            "nova", "pixel", "comet", "ember", "quartz", "raven", "glitch", "vortex", "cinder", "haze"
    };
    private static final String EXAMPLE_UUID = "00000000-0000-0000-0000-000000000000";
    private static final ObsData DEFAULT_OBS = new ObsData(true, "127.0.0.1", 4455, true);
    private static final LinkStateResponse MINECRAFT_UNLINKED = new LinkStateResponse("NONE", null, null);

    /** A session's initial Steam state. */
    record SteamState(boolean connected, boolean personalToken, boolean tokenShared,
                      boolean profilePrivate, boolean rateLimited, boolean offlineMode) {}

    @Getter
    private ChannelDto channelDto;
    /** Nullable: no detected game while the session has no binding. */
    @Getter
    private GameDto gameDto;
    @Getter
    private List<BindingDto> binding;
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
    /** Not reachable from the app's own UI — only the mock admin page edits this. */
    @Getter
    private long steamProfileCacheTtlMinutes = 15L;
    @Getter
    private boolean hasXboxProvider;
    @Getter
    private boolean hasXbox;
    private final boolean hasMinecraftProvider;
    private LinkStateResponse minecraftLink;
    private ObsData obsData;
    @Setter
    @Getter
    private boolean botEnabled = true;

    /**
     * @param steam     null when Steam isn't available to the channel
     * @param minecraft null when Minecraft isn't available to the channel
     * @param obs       null when OBS isn't available to the channel
     */
    MockData(ChannelDto channel, List<BindingDto> bindings, UserSettingsDto settings, List<ChannelDto> channels,
             SteamState steam, boolean hasXboxProvider, boolean hasXbox, LinkStateResponse minecraft, ObsData obs) {
        this.channelDto = channel;
        this.binding = bindings;
        // No binding (e.g. a random session that drew none) means no detected game either.
        this.gameDto = bindings.isEmpty() ? null : detectedGameOf(bindings.getFirst());
        this.userSettingsDto = settings;
        this.channelsList = channels.contains(channel) ? channels : withFirst(channel, channels);
        this.hasSteamProvider = steam != null;
        if (steam != null) {
            this.hasSteam = steam.connected();
            this.hasSteamPersonalToken = steam.personalToken();
            this.steamTokenShared = steam.tokenShared();
            this.steamProfilePrivate = steam.profilePrivate();
            this.steamRateLimited = steam.rateLimited();
            this.steamOfflineMode = steam.offlineMode();
        }
        this.hasXboxProvider = hasXboxProvider;
        this.hasXbox = hasXbox;
        this.hasMinecraftProvider = minecraft != null;
        this.minecraftLink = minecraft;
        this.obsData = obs;
    }

    // --- factories -----------------------------------------------------------------------------

    /**
     * Parses {@code jwt} as either a full custom config — a JSON object built by the mock login
     * form — or, for the quick-launch links, a single-digit index into {@link MockPresets}.
     * A malformed config falls back to preset "0" rather than failing the login.
     */
    public static MockData fromJwt(String jwt) {
        if (jwt != null && jwt.startsWith("{")) {
            try {
                return fromConfig(JSON.readValue(jwt, MockConfig.class));
            } catch (Exception e) {
                return fromJwt("0");
            }
        }
        int id = Integer.parseInt(jwt);
        List<ChannelDto> channels = MockPresets.CHANNELS;
        List<BindingDto> bindings = MockPresets.BINDINGS;
        return new MockData(
                channels.get(Math.min(id, channels.size() - 1)),
                List.of(bindings.get(Math.min(id, bindings.size() - 1))),
                MockPresets.DEFAULT_SETTINGS,
                id == 0 ? channels : List.of(),
                new SteamState(true, true, false, false, false, false),
                false, false, MINECRAFT_UNLINKED, DEFAULT_OBS);
    }

    private static MockData fromConfig(MockConfig config) {
        ChannelDto channel = new ChannelDto(MockPresets.uuidOf(config.username), config.twitchId, config.username,
                config.avatarUrl, config.live);

        MockConfig.Binding b = config.binding;
        BindingDto binding = new BindingDto(MockPresets.uuidOf(b.sourceName).toString(), b.status, b.sourceType,
                b.sourceName, b.twitchGameId, b.twitchGameName, b.ignored, b.cclEnabled, Set.copyOf(b.ccls),
                b.twEnabled, b.twOverride, Set.copyOf(b.tws));

        UserSettingsDto settings = new UserSettingsDto(
                config.settings.cclFeatureEnabled, Set.copyOf(config.settings.blockedCcls),
                null, null, Set.of(), false, false, false,
                null, null, Set.of(),
                AVAILABLE_CCLS, config.settings.twFeatureEnabled, Set.copyOf(config.settings.blockedTws), AVAILABLE_TWS);

        MockConfig.Steam steam = config.steam;
        MockData data = new MockData(channel, List.of(binding), settings, List.of(),
                new SteamState(steam.connected, steam.hasPersonalToken, steam.tokenShared,
                        steam.profilePrivate, steam.rateLimited, steam.offlineMode),
                true, config.xbox.connected,
                new LinkStateResponse(config.minecraft.status, config.minecraft.minecraftName,
                        config.minecraft.serviceAccountUsername),
                DEFAULT_OBS);
        data.setBotEnabled(config.botEnabled);
        return data;
    }

    /**
     * A fresh, randomly-populated channel — for every mock login without a full JSON config, so
     * several mock sessions at once get distinct-looking data instead of the same preset.
     */
    public static MockData random() {
        return random(RANDOM);
    }

    /**
     * Same as {@link #random()}, but seeded from {@code seed} so the same session token always
     * regenerates the same channel (e.g. after a restart lost the in-memory sessions).
     */
    public static MockData randomFrom(String seed) {
        return random(new Random(seed.hashCode()));
    }

    private static MockData random(Random random) {
        String username = RANDOM_NAME_PARTS[random.nextInt(RANDOM_NAME_PARTS.length)]
                + "_" + Integer.toHexString(random.nextInt(0x10000));
        ChannelDto channel = new ChannelDto(new UUID(random.nextLong(), random.nextLong()),
                String.valueOf(10_000_000 + random.nextInt(90_000_000)), username, MockPresets.AVATAR_URL,
                random.nextBoolean());

        boolean hasSteam = random.nextBoolean();
        boolean hasSteamPersonalToken = hasSteam && random.nextBoolean();
        // Draw order matters: randomFrom must keep regenerating the same session for a seed.
        List<BindingDto> bindings = MockPresets.BINDINGS.stream().filter(unused -> random.nextBoolean()).toList();
        boolean tokenShared = hasSteamPersonalToken && random.nextBoolean();
        boolean profilePrivate = hasSteam && random.nextBoolean();
        boolean rateLimited = hasSteam && random.nextBoolean();
        boolean offlineMode = hasSteam && random.nextBoolean();
        boolean hasXbox = random.nextBoolean();

        return new MockData(channel, bindings, MockPresets.DEFAULT_SETTINGS, List.of(),
                new SteamState(hasSteam, hasSteamPersonalToken, tokenShared, profilePrivate, rateLimited, offlineMode),
                true, hasXbox, MINECRAFT_UNLINKED, DEFAULT_OBS);
    }

    // --- reads ---------------------------------------------------------------------------------

    public ChannelListResponse getChannelList() {
        return new ChannelListResponse(channelDto.twitchId(), channelsList);
    }

    /** The dashboard as {@code username} sees it, bindings filtered by status and/or source. */
    public ChannelPageData getPage(String username, String status, String source) {
        List<BindingDto> bindings = binding.stream()
                .filter(b -> status == null || status.equalsIgnoreCase(b.status()))
                .filter(b -> source == null || source.equalsIgnoreCase(b.sourceType()))
                .toList();
        return new ChannelPageData(
                getChannelUser(), username, username.equals(channelDto.twitchUsername()),
                channelDto.live(), botEnabled, gameDto,
                new PagedBindings(0, bindings.isEmpty() ? 0 : 1, bindings.size(), bindings),
                AVAILABLE_CCLS, userSettingsDto.blockedCcls(), AVAILABLE_TWS, userSettingsDto.blockedTws(),
                status, source, steamData(), hasXboxProvider ? new XboxData(hasXbox) : null, minecraftData(), obsData,
                EXAMPLE_UUID);
    }

    private SteamData steamData() {
        return !hasSteamProvider ? null : new SteamData(hasSteam, hasSteamPersonalToken, steamTokenShared,
                steamProfilePrivate, steamRateLimited, steamOfflineMode, steamProfileCacheTtlMinutes);
    }

    private MinecraftData minecraftData() {
        return !hasMinecraftProvider ? null : new MinecraftData(minecraftLink.status(), minecraftLink.minecraftName(),
                minecraftLink.serviceAccountUsername());
    }

    public ChannelUserDto getChannelUser() {
        return new ChannelUserDto(channelDto.id().toString(), channelDto.twitchId(), channelDto.twitchUsername(),
                channelDto.profileImageUrl());
    }

    public UserSettingsDto getUserSettings() {
        return userSettingsDto;
    }

    public LinkStateResponse getMinecraftLink() {
        return minecraftLink;
    }

    // --- binding mutations ---------------------------------------------------------------------

    public void setBindingCclEnabled(String bindingId, boolean enabled) {
        updateBinding(bindingId, b -> BindingCopy.of(b).cclEnabled(enabled).build());
    }

    public void setBindingIgnored(String bindingId, boolean ignored) {
        updateBinding(bindingId, b -> BindingCopy.of(b).ignored(ignored).build());
    }

    public void deleteBinding(String bindingId) {
        binding = binding.stream().filter(b -> !b.id().equals(bindingId)).toList();
    }

    public void updateBindingGame(String bindingId, String twitchGameId, String twitchGameName, Set<String> ccls) {
        updateBinding(bindingId, b -> BindingCopy.of(b)
                .twitchGame(twitchGameId, twitchGameName).ccls(Set.copyOf(ccls)).build());
    }

    /** Custom trigger warnings override the channel-wide ones. */
    public void setBindingTws(String bindingId, Set<String> tws) {
        updateBinding(bindingId, b -> BindingCopy.of(b).tws(true, Set.copyOf(tws)).build());
    }

    public void resetBindingTws(String bindingId) {
        updateBinding(bindingId, b -> BindingCopy.of(b).tws(false, Set.of()).build());
    }

    public void setBindingTwEnabled(String bindingId, boolean enabled) {
        updateBinding(bindingId, b -> BindingCopy.of(b).twEnabled(enabled).build());
    }

    /** Applies {@code mapper} to the binding matching {@code bindingId}, leaving the others untouched. */
    private void updateBinding(String bindingId, UnaryOperator<BindingDto> mapper) {
        binding = binding.stream().map(b -> b.id().equals(bindingId) ? mapper.apply(b) : b).toList();
    }

    // --- settings mutations --------------------------------------------------------------------

    public void saveCclSettings(boolean enabled, Set<String> blockedCcls) {
        userSettingsDto = SettingsCopy.of(userSettingsDto).ccl(enabled, Set.copyOf(blockedCcls)).build();
    }

    public void saveTwSettings(boolean enabled, Set<String> blockedTws) {
        userSettingsDto = SettingsCopy.of(userSettingsDto).tw(enabled, Set.copyOf(blockedTws)).build();
    }

    public void saveNoGameSettings(String twitchGameId, String twitchGameName, Set<String> ccls,
                                   boolean applyOnStreamStart, boolean applyOnNoGame, boolean applyOnStreamEnd) {
        userSettingsDto = SettingsCopy.of(userSettingsDto)
                .noGame(twitchGameId, twitchGameName, Set.copyOf(ccls), applyOnStreamStart, applyOnNoGame, applyOnStreamEnd)
                .build();
    }

    public void saveIncompleteFallbackSettings(String twitchGameId, String twitchGameName, Set<String> ccls) {
        userSettingsDto = SettingsCopy.of(userSettingsDto)
                .incompleteFallback(twitchGameId, twitchGameName, Set.copyOf(ccls)).build();
    }

    // --- connections ---------------------------------------------------------------------------

    public void minecraftEnroll(String name) {
        minecraftLink = new LinkStateResponse("PENDING", name, null);
    }

    /** Simulates the invite being accepted in-game the moment it's checked — the mock has no game client to poll. */
    public void minecraftSync() {
        if (minecraftLink != null
                && ("PENDING".equals(minecraftLink.status()) || "REMOVED".equals(minecraftLink.status()))) {
            minecraftLink = new LinkStateResponse("ACCEPTED", minecraftLink.minecraftName(),
                    minecraftLink.serviceAccountUsername());
        }
    }

    public void minecraftDisconnect() {
        minecraftLink = MINECRAFT_UNLINKED;
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

    /** Mirrors catapult-api: blank host/port fall back to the defaults, a null password keeps the stored one. */
    public void saveObsSettings(boolean enabled, String host, Integer port, String password) {
        obsData = new ObsData(enabled,
                host == null || host.isBlank() ? DEFAULT_OBS.host() : host,
                port == null ? DEFAULT_OBS.port() : port,
                password != null || obsData != null && obsData.hasPassword());
    }

    // --- admin-only mutators: no UI path reaches these, only the mock admin page ----------------

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
        gameDto = new GameDto(gameDto == null ? null : gameDto.bindingId(), sourceName, sourceType);
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

    /** The binding the admin page's single-slot form edits — the first one, or null when there's none. */
    public BindingDto getPrimaryBinding() {
        return binding.isEmpty() ? null : binding.getFirst();
    }

    /**
     * Creates or fully replaces the admin page's binding slot (the first entry), including
     * {@code status} — which the dashboard never touches — and works even after that entry was
     * deleted, since the UI has no "add binding" action. Other bindings are left untouched.
     */
    public void replaceBinding(String status, String sourceType, String sourceName, String twitchGameId,
                               String twitchGameName, boolean ignored, boolean cclEnabled, Set<String> ccls,
                               boolean twEnabled, boolean twOverride, Set<String> tws) {
        BindingDto primary = getPrimaryBinding();
        String id = primary != null ? primary.id() : MockPresets.uuidOf(sourceName).toString();
        BindingDto replaced = new BindingDto(id, status, sourceType, sourceName, twitchGameId, twitchGameName,
                ignored, cclEnabled, Set.copyOf(ccls), twEnabled, twOverride, Set.copyOf(tws));
        List<BindingDto> rest = primary != null ? binding.subList(1, binding.size()) : binding;
        binding = Stream.concat(Stream.of(replaced), rest.stream()).toList();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static GameDto detectedGameOf(BindingDto binding) {
        return new GameDto(binding.id(), binding.sourceName(), binding.sourceType());
    }

    private static List<ChannelDto> withFirst(ChannelDto first, List<ChannelDto> others) {
        List<ChannelDto> channels = new ArrayList<>(others);
        channels.addFirst(first);
        return channels;
    }

    /** Copy-and-modify for the immutable BindingDto. */
    private static final class BindingCopy {
        private final BindingDto from;
        private String twitchGameId;
        private String twitchGameName;
        private boolean ignored;
        private boolean cclEnabled;
        private Set<String> ccls;
        private boolean twEnabled;
        private boolean twOverride;
        private Set<String> tws;

        private BindingCopy(BindingDto from) {
            this.from = from;
            this.twitchGameId = from.twitchGameId();
            this.twitchGameName = from.twitchGameName();
            this.ignored = from.ignored();
            this.cclEnabled = from.cclEnabled();
            this.ccls = from.ccls();
            this.twEnabled = from.twEnabled();
            this.twOverride = from.twOverride();
            this.tws = from.tws();
        }

        static BindingCopy of(BindingDto from) {
            return new BindingCopy(from);
        }

        BindingCopy twitchGame(String id, String name) {
            this.twitchGameId = id;
            this.twitchGameName = name;
            return this;
        }

        BindingCopy ignored(boolean ignored) {
            this.ignored = ignored;
            return this;
        }

        BindingCopy cclEnabled(boolean cclEnabled) {
            this.cclEnabled = cclEnabled;
            return this;
        }

        BindingCopy ccls(Set<String> ccls) {
            this.ccls = ccls;
            return this;
        }

        BindingCopy twEnabled(boolean twEnabled) {
            this.twEnabled = twEnabled;
            return this;
        }

        BindingCopy tws(boolean override, Set<String> tws) {
            this.twOverride = override;
            this.tws = tws;
            return this;
        }

        BindingDto build() {
            return new BindingDto(from.id(), from.status(), from.sourceType(), from.sourceName(), twitchGameId,
                    twitchGameName, ignored, cclEnabled, ccls, twEnabled, twOverride, tws);
        }
    }

    /** Copy-and-modify for the immutable UserSettingsDto, one settings card at a time. */
    private static final class SettingsCopy {
        private final UserSettingsDto from;
        private boolean cclEnabled;
        private Set<String> blockedCcls;
        private String noGameId;
        private String noGameName;
        private Set<String> noGameCcls;
        private boolean applyOnStreamStart;
        private boolean applyOnNoGame;
        private boolean applyOnStreamEnd;
        private String fallbackId;
        private String fallbackName;
        private Set<String> fallbackCcls;
        private boolean twEnabled;
        private Set<String> blockedTws;

        private SettingsCopy(UserSettingsDto from) {
            this.from = from;
            this.cclEnabled = from.cclFeatureEnabled();
            this.blockedCcls = from.blockedCcls();
            this.noGameId = from.noGameTwitchGameId();
            this.noGameName = from.noGameTwitchGameName();
            this.noGameCcls = from.noGameCcls();
            this.applyOnStreamStart = from.applyDefaultOnStreamStart();
            this.applyOnNoGame = from.applyDefaultOnNoGame();
            this.applyOnStreamEnd = from.applyDefaultOnStreamEnd();
            this.fallbackId = from.incompleteFallbackTwitchGameId();
            this.fallbackName = from.incompleteFallbackTwitchGameName();
            this.fallbackCcls = from.incompleteFallbackCcls();
            this.twEnabled = from.twFeatureEnabled();
            this.blockedTws = from.blockedTws();
        }

        static SettingsCopy of(UserSettingsDto from) {
            return new SettingsCopy(from);
        }

        SettingsCopy ccl(boolean enabled, Set<String> blocked) {
            this.cclEnabled = enabled;
            this.blockedCcls = blocked;
            return this;
        }

        SettingsCopy tw(boolean enabled, Set<String> blocked) {
            this.twEnabled = enabled;
            this.blockedTws = blocked;
            return this;
        }

        SettingsCopy noGame(String id, String name, Set<String> ccls,
                            boolean onStreamStart, boolean onNoGame, boolean onStreamEnd) {
            this.noGameId = id;
            this.noGameName = name;
            this.noGameCcls = ccls;
            this.applyOnStreamStart = onStreamStart;
            this.applyOnNoGame = onNoGame;
            this.applyOnStreamEnd = onStreamEnd;
            return this;
        }

        SettingsCopy incompleteFallback(String id, String name, Set<String> ccls) {
            this.fallbackId = id;
            this.fallbackName = name;
            this.fallbackCcls = ccls;
            return this;
        }

        UserSettingsDto build() {
            return new UserSettingsDto(cclEnabled, blockedCcls, noGameId, noGameName, noGameCcls,
                    applyOnStreamStart, applyOnNoGame, applyOnStreamEnd, fallbackId, fallbackName, fallbackCcls,
                    from.availableCcls(), twEnabled, blockedTws, from.availableTws());
        }
    }
}
