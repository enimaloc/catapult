package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.userapi.ApiV2;
import fr.enimaloc.catapult.api.userapi.DevBackdoorResolver;
import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.dto.channel.CclDto;
import fr.enimaloc.catapult.common.dto.channel.ChannelPageData;
import fr.enimaloc.catapult.common.dto.channel.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingCurrentDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingProposalDto;
import fr.enimaloc.catapult.common.dto.channel.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.channel.GameDto;
import fr.enimaloc.catapult.common.dto.channel.MinecraftData;
import fr.enimaloc.catapult.common.dto.channel.ObsData;
import fr.enimaloc.catapult.common.dto.channel.TwitchatData;
import fr.enimaloc.catapult.common.dto.channel.PagedBindings;
import fr.enimaloc.catapult.common.dto.channel.StatusData;
import fr.enimaloc.catapult.common.dto.channel.SteamData;
import fr.enimaloc.catapult.common.dto.channel.TwDto;
import fr.enimaloc.catapult.common.dto.channel.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.channel.XboxData;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.steam.SteamApiClient;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.dtdd.DtddMappingProposalRepository;
import fr.enimaloc.catapult.repository.tw.TwDefinitionRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.connections.SteamProfileDiagnostics;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import fr.enimaloc.catapult.service.igdb.AdminCclService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.minecraft.MinecraftFriendService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds what the channel dashboard shows (status, bindings page, settings, DTDD mapping) for
 * a viewer already allowed on the channel — {@link ApiChannelDataController} only checks access.
 */
@Component
@RequiredArgsConstructor
public class ChannelDashboardAssembler {
    static final int BINDINGS_PAGE_SIZE = 20;
    /** Source types {@link DevBackdoorResolver} can encode into a "try it out" uuid. */
    private static final Set<GameBinding.SourceType> BACKDOOR_ENCODABLE =
            Set.of(GameBinding.SourceType.STEAM, GameBinding.SourceType.XBOX);

    private final GameBindingRepository gameBindingRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final StreamStateService streamStateService;
    private final GameStateService gameStateService;
    private final AdminCclService adminCclService;
    private final TwDefinitionRepository twDefinitionRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final Optional<SteamApiClient> steamApiClient;
    private final SteamProfileDiagnostics steamDiagnostics;
    private final IgdbService igdbService;
    private final DtddGameMappingRepository dtddMappingRepo;
    private final DtddGameCacheRepository dtddGameCacheRepo;
    private final DtddMappingProposalRepository dtddProposalRepo;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final DevBackdoorResolver devBackdoorResolver;
    // Only present when minecraft.enabled (the service is @ConditionalOnBooleanProperty).
    private final Optional<MinecraftFriendService> minecraftFriendService;
    private final TwitchatWidgetSettingsService twitchatWidgetSettingsService;

    @Value("${xbox.enabled:false}")
    private boolean xboxEnabled;

    // --- status ----------------------------------------------------------------------------------

    public StatusData status(UserAccount viewer, UserAccount channel, String username) {
        return new StatusData(username, isOwner(viewer, channel), channel.isBotEnabled(),
                streamStateService.isLive(channel), currentGame(channel).orElse(null));
    }

    // --- dashboard page --------------------------------------------------------------------------

    /**
     * The whole dashboard. Connection details (Steam diagnostics, Minecraft link, OBS) are only
     * filled in for the channel's owner; a provider that isn't available at all is null.
     */
    public ChannelPageData page(UserAccount viewer, UserAccount channel, String username,
                                int page, String status, String source) {
        boolean owner = isOwner(viewer, channel);
        Optional<DetectedGame> detected = gameStateService.getLastKnownGame(channel);
        Optional<UserSettings> settings = userSettingsRepository.findById(channel.getId());

        return new ChannelPageData(
                channelUser(channel),
                username,
                owner,
                streamStateService.isLive(channel),
                channel.isBotEnabled(),
                detected.map(game -> toGameDto(channel, game)).orElse(null),
                bindings(channel, page, status, source),
                availableCcls(),
                settings.map(UserSettings::getBlockedCcls).orElse(Set.of()),
                availableTws(),
                settings.map(UserSettings::getBlockedTws).orElse(Set.of()),
                status,
                source,
                steamData(channel, owner),
                xboxEnabled ? new XboxData(hasXboxToken(channel)) : null,
                owner ? minecraftData(channel) : null,
                owner ? obsData(channel) : null,
                owner ? twitchatData(channel) : null,
                exampleUuid(channel, detected));
    }

    private static ChannelUserDto channelUser(UserAccount channel) {
        return new ChannelUserDto(channel.getId().toString(), channel.getTwitchId(), channel.getTwitchUsername(),
                channel.getProfileImageUrl());
    }

    /** One page of bindings, filtered by status (which wins) or source type. */
    private PagedBindings bindings(UserAccount channel, int page, String status, String source) {
        PageRequest request = PageRequest.of(page, BINDINGS_PAGE_SIZE);
        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                    channel, GameBinding.Status.valueOf(status.toUpperCase()), request);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                    channel, GameBinding.SourceType.valueOf(source.toUpperCase()), request);
        } else {
            bindings = gameBindingRepository.findByUser(channel, request);
        }
        List<BindingDto> content = bindings.getContent().stream().map(ChannelDashboardAssembler::toBindingDto).toList();
        return new PagedBindings(bindings.getNumber(), bindings.getTotalPages(), bindings.getTotalElements(), content);
    }

    private static BindingDto toBindingDto(GameBinding binding) {
        return new BindingDto(binding.getId().toString(), binding.getStatus().name(), binding.getSourceType().name(),
                binding.getSourceName(), binding.getTwitchGameId(), binding.getTwitchGameName(), binding.isIgnored(),
                binding.isCclEnabled(), binding.getCcls(), binding.isTwEnabled(), binding.isTwOverride(),
                binding.getTws());
    }

    /** Steam state, null without Steam; only the owner's profile is probed live. */
    private SteamData steamData(UserAccount channel, boolean owner) {
        if (!steamDiagnostics.available()) {
            return null;
        }
        SteamProfileDto steam = steamDiagnostics.snapshot(channel, owner);
        return new SteamData(steam.hasSteam(), steam.hasPersonalToken(), steam.tokenShared(), steam.profilePrivate(),
                steam.rateLimited(), steam.offlineMode(), steam.ttlMinutes());
    }

    private boolean hasXboxToken(UserAccount channel) {
        return oAuthTokenRepository.findByUserAndProvider(channel, OAuthToken.Provider.XBOX).isPresent();
    }

    /** The Minecraft link ("NONE" when unlinked, as the connect endpoint says), null without Minecraft. */
    private MinecraftData minecraftData(UserAccount channel) {
        if (minecraftFriendService.isEmpty()) {
            return null;
        }
        // getLink() fetches the service account too, so its username is readable here (OSIV is off).
        Optional<MinecraftFriendLink> link = minecraftFriendService.get().getLink(channel);
        return link.map(l -> new MinecraftData(l.getStatus().name(), l.getMinecraftName(),
                        l.getServiceAccount().getMinecraftUsername()))
                .orElseGet(() -> new MinecraftData("NONE", null, null));
    }

    /** The Twitchat relay's OBS settings; getOrCreate() may insert the row on first view. */
    private ObsData obsData(UserAccount channel) {
        var settings = twitchatWidgetSettingsService.getOrCreate(channel);
        return new ObsData(settings.isEnabled(), settings.getObsHost(), settings.getObsPort(),
                settings.getObsPasswordEncrypted() != null);
    }

    /** The Twitchat API the channel's pages speak, from the same settings row as obsData(). */
    private TwitchatData twitchatData(UserAccount channel) {
        return TwitchatData.of(twitchatWidgetSettingsService.getOrCreate(channel).getTwitchatBranch());
    }

    /**
     * A "try it out"-able uuid for the Swagger docs' {@code uuid} path parameter (see
     * SwaggerUiJwtTransformer / twitchat-settings.html, which stores it in
     * {@code localStorage['catapult_widget_uuid']} for a logged-in dashboard user):
     * <ol>
     *   <li>the currently detected game, backdoor-encoded, if its source type supports it;
     *   <li>otherwise the most recently touched encodable binding on record;
     *   <li>otherwise the account's own widget token;
     *   <li>and only then the generic example anonymous Swagger visitors see.
     * </ol>
     */
    private String exampleUuid(UserAccount channel, Optional<DetectedGame> detected) {
        Optional<UUID> encoded = detected
                .filter(game -> BACKDOOR_ENCODABLE.contains(game.getSourceType()))
                .flatMap(game -> devBackdoorResolver.encode(game.getSourceType(), game.getSourceId()));
        if (encoded.isEmpty()) {
            encoded = gameBindingRepository
                    .findFirstByUserAndSourceTypeInOrderByUpdatedAtDesc(channel, BACKDOOR_ENCODABLE)
                    .flatMap(binding -> devBackdoorResolver.encode(binding.getSourceType(), binding.getSourceId()));
        }
        return encoded.map(UUID::toString)
                .orElseGet(() -> channel.getWidgetToken() != null
                        ? channel.getWidgetToken().toString()
                        : ApiV2.GENERIC_UUID_EXAMPLE);
    }

    // --- settings --------------------------------------------------------------------------------

    /** The channel's settings, defaults when it never saved any. */
    public UserSettingsDto settings(UserAccount channel) {
        UserSettings settings = userSettingsRepository.findById(channel.getId()).orElseGet(() -> {
            UserSettings defaults = new UserSettings();
            defaults.setUserId(channel.getId());
            return defaults;
        });
        return new UserSettingsDto(
                settings.isCclFeatureEnabled(),
                settings.getBlockedCcls(),
                settings.getNoGameTwitchGameId(),
                settings.getNoGameTwitchGameName(),
                settings.getNoGameCcls(),
                settings.isApplyDefaultOnStreamStart(),
                settings.isApplyDefaultOnNoGame(),
                settings.isApplyDefaultOnStreamEnd(),
                settings.getIncompleteFallbackTwitchGameId(),
                settings.getIncompleteFallbackTwitchGameName(),
                settings.getIncompleteFallbackCcls(),
                availableCcls(),
                settings.isTwFeatureEnabled(),
                settings.getBlockedTws(),
                availableTws());
    }

    // --- DTDD mapping ----------------------------------------------------------------------------

    /**
     * The DTDD mapping of the game the channel is playing, as {@code viewer} sees it: the
     * current mapping, their own pending proposal, and whether they may validate it directly
     * (an unverified mapping nobody else has a proposal pending on).
     */
    public DtddMappingStatusDto dtddMapping(UserAccount viewer, UserAccount channel) {
        Optional<String> igdbId = gameStateService.getLastKnownGame(channel)
                .flatMap(this::igdbGame)
                .map(IgdbService.IgdbGame::id);
        if (igdbId.isEmpty()) {
            return new DtddMappingStatusDto(null, null, false, null);
        }
        String id = igdbId.get();

        Optional<DtddGameMapping> mapping = dtddMappingRepo.findById(id);
        Optional<DtddMappingProposal> myProposal = dtddProposalRepo
                .findFirstByProposerAndIgdbIdAndStatus(viewer, id, DtddMappingProposal.Status.PENDING);
        long pendingProposals = dtddProposalRepo.countByIgdbIdAndStatus(id, DtddMappingProposal.Status.PENDING);
        boolean canValidate = mapping.isPresent() && !mapping.get().isVerified() && pendingProposals == 0;

        DtddMappingCurrentDto current = mapping.map(m -> new DtddMappingCurrentDto(
                m.getDtddId(), dtddName(m.getDtddId()), m.getConfidence(), m.isVerified())).orElse(null);
        DtddMappingProposalDto pending = myProposal.map(p ->
                new DtddMappingProposalDto(p.getId(), p.getProposedDtddId(), p.getReason())).orElse(null);

        return new DtddMappingStatusDto(current, pending, canValidate, id);
    }

    /** IGDB entry of a detected game: by app id for Steam games, by name otherwise. */
    private Optional<IgdbService.IgdbGame> igdbGame(DetectedGame game) {
        return game.getSourceType() == GameBinding.SourceType.STEAM
                ? igdbService.findBySteamAppId(game.getSourceId())
                : igdbService.findByName(game.getSourceName());
    }

    private String dtddName(Long dtddId) {
        return dtddId == null ? null : dtddGameCacheRepo.findById(dtddId).map(DtddGameCache::getName).orElse(null);
    }

    // --- Steam profile cache ---------------------------------------------------------------------

    /** Forgets the channel's cached Steam profile, so the next read asks Steam again. */
    public void refreshSteamProfileCache(UserAccount channel) {
        if (channel.getSteamId() == null) {
            return;
        }
        String personalToken = channel.getSteamPersonalToken() != null
                ? tokenEncryptionService.decrypt(channel.getSteamPersonalToken()) : null;
        steamApiClient.ifPresent(client -> client.invalidateProfileCache(channel.getSteamId(), personalToken));
    }

    // --- shared ----------------------------------------------------------------------------------

    private static boolean isOwner(UserAccount viewer, UserAccount channel) {
        return viewer.getId().equals(channel.getId());
    }

    private Optional<GameDto> currentGame(UserAccount channel) {
        return gameStateService.getLastKnownGame(channel).map(game -> toGameDto(channel, game));
    }

    /** The detected game, with the id of the channel's binding for it when there is one. */
    private GameDto toGameDto(UserAccount channel, DetectedGame game) {
        String bindingId = gameBindingRepository
                .findByUserAndSourceIdAndSourceType(channel, game.getSourceId(), game.getSourceType())
                .map(binding -> binding.getId().toString())
                .orElse(null);
        return new GameDto(bindingId, game.getSourceName(), game.getSourceType().name());
    }

    private List<CclDto> availableCcls() {
        return adminCclService.getAllCcls().stream().map(c -> new CclDto(c.getId(), c.getName())).toList();
    }

    private List<TwDto> availableTws() {
        return twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(t -> new TwDto(t.getId(), t.getLabel()))
                .toList();
    }
}
