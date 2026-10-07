package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.BindingDto;
import fr.enimaloc.catapult.common.dto.CclDto;
import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.ChannelUserDto;
import fr.enimaloc.catapult.common.dto.DtddMappingCurrentDto;
import fr.enimaloc.catapult.common.dto.DtddMappingProposalDto;
import fr.enimaloc.catapult.common.dto.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.GameDto;
import fr.enimaloc.catapult.common.dto.MinecraftData;
import fr.enimaloc.catapult.common.dto.ObsData;
import fr.enimaloc.catapult.common.dto.PagedBindings;
import fr.enimaloc.catapult.common.dto.StatusData;
import fr.enimaloc.catapult.common.dto.SteamData;
import fr.enimaloc.catapult.common.dto.TwDto;
import fr.enimaloc.catapult.common.dto.UserSettingsDto;
import fr.enimaloc.catapult.common.dto.XboxData;
import fr.enimaloc.catapult.domain.DtddGameCache;
import fr.enimaloc.catapult.domain.DtddGameMapping;
import fr.enimaloc.catapult.domain.DtddMappingProposal;
import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.api.userapi.ApiV2;
import fr.enimaloc.catapult.api.userapi.DevBackdoorResolver;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.repository.DtddGameCacheRepository;
import fr.enimaloc.catapult.repository.DtddGameMappingRepository;
import fr.enimaloc.catapult.repository.DtddMappingProposalRepository;
import fr.enimaloc.catapult.repository.GameBindingRepository;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.TwDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.AdminCclService;
import fr.enimaloc.catapult.service.ChannelAccessService;
import fr.enimaloc.catapult.service.ConnectionEventService;
import fr.enimaloc.catapult.service.GameStateService;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.service.IgdbService;
import fr.enimaloc.catapult.service.MinecraftFriendService;
import fr.enimaloc.catapult.service.StreamStateService;
import fr.enimaloc.catapult.service.TwitchCategory;
import fr.enimaloc.catapult.service.TwitchService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/channels/{username}")
@RequiredArgsConstructor
public class ApiChannelDataController {

    private final UserAccountRepository userAccountRepository;
    private final GameBindingRepository gameBindingRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final ChannelAccessService channelAccessService;
    private final StreamStateService streamStateService;
    private final GameStateService gameStateService;
    private final AdminCclService adminCclService;
    private final TwDefinitionRepository twDefinitionRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final SteamApiKeyRotator steamApiKeyRotator;
    private final Optional<SteamApiClient> steamApiClient;
    private final TwitchService twitchService;
    private final ActivityLogService activityLogService;
    private final ConnectionEventService connectionEventService;
    private final IgdbService igdbService;
    private final DtddGameMappingRepository dtddMappingRepo;
    private final DtddGameCacheRepository dtddGameCacheRepo;
    private final DtddMappingProposalRepository dtddProposalRepo;
    private final OAuthTokenRepository oAuthTokenRepository;
    private final DevBackdoorResolver devBackdoorResolver;
    // Only present when minecraft.enabled (the service is @ConditionalOnBooleanProperty).
    private final Optional<MinecraftFriendService> minecraftFriendService;
    private final TwitchatWidgetSettingsService twitchatWidgetSettingsService;

    private static final Set<GameBinding.SourceType> BACKDOOR_ENCODABLE =
            Set.of(GameBinding.SourceType.STEAM, GameBinding.SourceType.XBOX);

    @Value("${xbox.enabled:false}")
    private boolean xboxEnabled;

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount channelUser = resolveAndCheckAccess(username, jwt);
        return activityLogService.subscribe(channelUser.getId());
    }

    @GetMapping(value = "/connections", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connections(@PathVariable String username, @AuthenticationPrincipal Jwt jwt) {
        UserAccount channelUser = resolveAndCheckAccess(username, jwt);
        return connectionEventService.subscribe(channelUser.getId());
    }

    private UserAccount resolveAndCheckAccess(String username, Jwt jwt) {
        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return channelUser;
    }

    private GameDto toGameDto(UserAccount channelUser, DetectedGame game) {
        String bindingId = gameBindingRepository
                .findByUserAndSourceIdAndSourceType(channelUser, game.getSourceId(), game.getSourceType())
                .map(binding -> binding.getId().toString())
                .orElse(null);
        return new GameDto(bindingId, game.getSourceName(), game.getSourceType().name());
    }

    @GetMapping
    public ChannelPageData channelPage(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source) {

        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        boolean isOwner = viewer.getId().equals(channelUser.getId());

        Optional<DetectedGame> currentGame = gameStateService.getLastKnownGame(channelUser);
        GameDto currentGameDto = currentGame.map(g -> toGameDto(channelUser, g)).orElse(null);
        String exampleUuid = resolveExampleUuid(channelUser, currentGame);

        PageRequest pageRequest = PageRequest.of(page, 20);
        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                    channelUser, GameBinding.Status.valueOf(status.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                    channelUser, GameBinding.SourceType.valueOf(source.toUpperCase()), pageRequest);
        } else {
            bindings = gameBindingRepository.findByUser(channelUser, pageRequest);
        }

        List<BindingDto> bindingDtos = bindings.getContent().stream()
                .map(b -> new BindingDto(
                        b.getId().toString(),
                        b.getStatus().name(),
                        b.getSourceType().name(),
                        b.getSourceName(),
                        b.getTwitchGameId(),
                        b.getTwitchGameName(),
                        b.isIgnored(),
                        b.isCclEnabled(),
                        b.getCcls(),
                        b.isTwEnabled(),
                        b.isTwOverride(),
                        b.getTws()
                ))
                .toList();

        PagedBindings pagedBindings = new PagedBindings(
                bindings.getNumber(),
                bindings.getTotalPages(),
                bindings.getTotalElements(),
                bindingDtos
        );

        Set<String> blockedCcls = userSettingsRepository.findById(channelUser.getId())
                .map(UserSettings::getBlockedCcls)
                .orElse(Set.of());

        Set<String> blockedTws = userSettingsRepository.findById(channelUser.getId())
                .map(UserSettings::getBlockedTws)
                .orElse(Set.of());

        boolean hasSteamProvider = steamApiClient.isPresent();
        boolean hasSteam = hasSteamProvider && channelUser.getSteamId() != null;
        long steamProfileCacheTtlMinutes = steamApiClient.map(c -> c.getProfileCacheTtl().toMinutes()).orElse(15L);
        boolean hasSteamPersonalToken = channelUser.getSteamPersonalToken() != null;
        boolean steamTokenShared = channelUser.isSteamTokenShared();
        boolean steamProfilePrivate = false;
        boolean steamRateLimited = false;
        boolean steamOfflineMode = false;

        if (hasSteam && isOwner) {
            String decryptedToken = hasSteamPersonalToken
                    ? tokenEncryptionService.decrypt(channelUser.getSteamPersonalToken())
                    : null;
            SteamApiClient.SteamProfileStatus profileStatus = steamApiClient.map(c -> {
                try {
                    return c.getProfileStatus(channelUser.getSteamId(), decryptedToken)
                            .orTimeout(2, TimeUnit.SECONDS)
                            .exceptionally(e -> new SteamApiClient.SteamProfileStatus(false, false))
                            .join();
                } catch (Exception e) {
                    return new SteamApiClient.SteamProfileStatus(false, false);
                }
            }).orElse(new SteamApiClient.SteamProfileStatus(true, false));
            steamProfilePrivate = !profileStatus.profilePublic();
            steamOfflineMode = profileStatus.offlineMode();
            boolean isRateLimited = steamApiClient.map(SteamApiClient::isRateLimited).orElse(false)
                    || steamApiKeyRotator.isAllKeysBlocked();
            if (steamProfilePrivate && isRateLimited) {
                steamRateLimited = true;
                steamProfilePrivate = false;
            }
        }

        boolean hasXboxProvider = xboxEnabled;
        boolean hasXbox = hasXboxProvider
                && oAuthTokenRepository.findByUserAndProvider(channelUser, OAuthToken.Provider.XBOX).isPresent();

        // getLink() fetches the service account too, so its username is readable here (OSIV is off).
        MinecraftFriendLink minecraftLink = minecraftFriendService
                .flatMap(service -> service.getLink(channelUser))
                .orElse(null);

        // Owner-only (the integrations tab is), and getOrCreate() may insert a row.
        var twitchatSettings = isOwner ? twitchatWidgetSettingsService.getOrCreate(channelUser) : null;

        ChannelUserDto channelUserDto = new ChannelUserDto(
                channelUser.getId().toString(),
                channelUser.getTwitchId(),
                channelUser.getTwitchUsername(),
                channelUser.getProfileImageUrl()
        );

        List<TwDto> availableTws = twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(t -> new TwDto(t.getId(), t.getLabel()))
                .toList();

        return new ChannelPageData(
                channelUserDto,
                username,
                isOwner,
                streamStateService.isLive(channelUser),
                channelUser.isBotEnabled(),
                currentGameDto,
                pagedBindings,
                adminCclService.getAllCcls().stream()
                        .map(c -> new CclDto(c.getId(), c.getName()))
                        .toList(),
                blockedCcls,
                availableTws,
                blockedTws,
                status,
                source,
                !hasSteamProvider ? null : new SteamData(
                        hasSteam,
                        hasSteamPersonalToken,
                        steamTokenShared,
                        steamProfilePrivate,
                        steamRateLimited,
                        steamOfflineMode,
                        steamProfileCacheTtlMinutes
                ),
                !hasXboxProvider ? null : new XboxData(
                        hasXbox
                ),
                minecraftFriendService.isEmpty() ? null : new MinecraftData(
                        // "NONE" when unlinked, same as ApiMinecraftConnectController's status.
                        minecraftLink == null ? "NONE" : minecraftLink.getStatus().name(),
                        minecraftLink == null ? null : minecraftLink.getMinecraftName(),
                        minecraftLink == null ? null : minecraftLink.getServiceAccount().getMinecraftUsername()
                ),
                twitchatSettings == null ? null : new ObsData(
                        twitchatSettings.isEnabled(),
                        twitchatSettings.getObsHost(),
                        twitchatSettings.getObsPort(),
                        twitchatSettings.getObsPasswordEncrypted() != null
                ),
                exampleUuid
        );
    }

    /**
     * A "try it out"-able uuid for the Swagger docs' {@code uuid} path parameter (see
     * SwaggerUiJwtTransformer / twitchat-settings.html, which stores this in
     * {@code localStorage['catapult_widget_uuid']} for a logged-in dashboard user):
     * <ol>
     *   <li>the currently detected game, backdoor-encoded, if its source type supports it — more
     *       demonstrative than the widget token alone, which would still resolve correctly but
     *       just look like the same live data either way;
     *   <li>otherwise the most recently touched encodable binding on record;
     *   <li>otherwise the account's own widget token — this is the actual point of the feature:
     *       a real, logged-in user should never fall through to the generic shared example.
     * </ol>
     * The generic example (ApiV2.GENERIC_UUID_EXAMPLE, baked into the spec as the schema default)
     * is what an anonymous Swagger visitor sees — this method is never involved for them, since
     * nothing here runs unless they're on their own /channels/{username} dashboard page.
     */
    private String resolveExampleUuid(UserAccount channelUser, Optional<DetectedGame> currentGame) {
        Optional<UUID> encoded = currentGame
                .filter(game -> BACKDOOR_ENCODABLE.contains(game.getSourceType()))
                .flatMap(game -> devBackdoorResolver.encode(game.getSourceType(), game.getSourceId()));

        if (encoded.isEmpty()) {
            encoded = gameBindingRepository
                    .findFirstByUserAndSourceTypeInOrderByUpdatedAtDesc(channelUser, BACKDOOR_ENCODABLE)
                    .flatMap(binding -> devBackdoorResolver.encode(binding.getSourceType(), binding.getSourceId()));
        }

        return encoded.map(UUID::toString)
                .orElseGet(() -> channelUser.getWidgetToken() != null
                        ? channelUser.getWidgetToken().toString()
                        : ApiV2.GENERIC_UUID_EXAMPLE);
    }

    @PostMapping("/steam/refresh-profile-cache")
    public ResponseEntity<Void> refreshProfileCache(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {
        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!viewer.getId().equals(channelUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (channelUser.getSteamId() != null) {
            String personalToken = channelUser.getSteamPersonalToken() != null
                    ? tokenEncryptionService.decrypt(channelUser.getSteamPersonalToken())
                    : null;
            steamApiClient.ifPresent(c -> c.invalidateProfileCache(channelUser.getSteamId(), personalToken));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public StatusData statusFragment(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        boolean isOwner = viewer.getId().equals(channelUser.getId());
        Optional<DetectedGame> currentGame = gameStateService.getLastKnownGame(channelUser);
        GameDto currentGameDto = currentGame.map(g -> toGameDto(channelUser, g)).orElse(null);

        return new StatusData(
                username,
                isOwner,
                channelUser.isBotEnabled(),
                streamStateService.isLive(channelUser),
                currentGameDto
        );
    }

    @GetMapping("/settings")
    public UserSettingsDto settings(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        UserSettings settings = userSettingsRepository.findById(channelUser.getId())
                .orElseGet(() -> {
                    UserSettings defaults = new UserSettings();
                    defaults.setUserId(channelUser.getId());
                    return defaults;
                });

        List<CclDto> ccls = adminCclService.getAllCcls().stream()
                .map(c -> new CclDto(c.getId(), c.getName()))
                .toList();

        List<TwDto> tws = twDefinitionRepository.findAllByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(t -> new TwDto(t.getId(), t.getLabel()))
                .toList();

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
                ccls,
                settings.isTwFeatureEnabled(),
                settings.getBlockedTws(),
                tws
        );
    }

    @GetMapping(value = "/games/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TwitchCategory> searchGames(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String q) {

        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (q.isBlank()) return List.of();
        return twitchService.searchCategories(viewer, q);
    }

    @GetMapping("/dtdd-mapping")
    public DtddMappingStatusDto dtddMappingStatus(
            @PathVariable String username,
            @AuthenticationPrincipal Jwt jwt) {

        UUID viewerId = UUID.fromString(jwt.getSubject());
        UserAccount viewer = userAccountRepository.findById(viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        UserAccount channelUser = userAccountRepository.findByTwitchUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!channelAccessService.canAccess(viewer, channelUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Optional<DetectedGame> currentGame = gameStateService.getLastKnownGame(channelUser);
        if (currentGame.isEmpty()) {
            return new DtddMappingStatusDto(null, null, false, null);
        }

        DetectedGame game = currentGame.get();
        Optional<IgdbService.IgdbGame> igdbGame;
        if (game.getSourceType() == GameBinding.SourceType.STEAM) {
            igdbGame = igdbService.findBySteamAppId(game.getSourceId());
        } else {
            igdbGame = igdbService.findByName(game.getSourceName());
        }
        if (igdbGame.isEmpty()) {
            return new DtddMappingStatusDto(null, null, false, null);
        }

        String igdbId = igdbGame.get().id();
        Optional<DtddGameMapping> mappingOpt = dtddMappingRepo.findById(igdbId);
        Optional<DtddMappingProposal> myProposal = dtddProposalRepo
                .findFirstByProposerAndIgdbIdAndStatus(viewer, igdbId, DtddMappingProposal.Status.PENDING);
        long otherPending = dtddProposalRepo.countByIgdbIdAndStatus(igdbId, DtddMappingProposal.Status.PENDING);
        boolean canValidate = mappingOpt.isPresent()
                && !mappingOpt.get().isVerified()
                && otherPending == 0;

        DtddMappingCurrentDto current = mappingOpt.map(m -> {
            String name = m.getDtddId() != null
                    ? dtddGameCacheRepo.findById(m.getDtddId()).map(DtddGameCache::getName).orElse(null)
                    : null;
            return new DtddMappingCurrentDto(m.getDtddId(), name, m.getConfidence(), m.isVerified());
        }).orElse(null);

        DtddMappingProposalDto pending = myProposal.map(p ->
                new DtddMappingProposalDto(p.getId(), p.getProposedDtddId(), p.getReason())).orElse(null);

        return new DtddMappingStatusDto(current, pending, canValidate, igdbId);
    }

}
