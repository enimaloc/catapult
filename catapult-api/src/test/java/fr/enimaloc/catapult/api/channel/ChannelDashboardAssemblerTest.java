package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.userapi.ApiV2;
import fr.enimaloc.catapult.api.userapi.DevBackdoorResolver;
import fr.enimaloc.catapult.common.dto.ChannelPageData;
import fr.enimaloc.catapult.common.dto.DtddMappingStatusDto;
import fr.enimaloc.catapult.common.dto.GameDto;
import fr.enimaloc.catapult.common.dto.MinecraftData;
import fr.enimaloc.catapult.common.dto.ObsData;
import fr.enimaloc.catapult.common.dto.StatusData;
import fr.enimaloc.catapult.common.dto.SteamData;
import fr.enimaloc.catapult.common.dto.UserSettingsDto;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.dtdd.DtddGameCache;
import fr.enimaloc.catapult.domain.dtdd.DtddGameMapping;
import fr.enimaloc.catapult.domain.dtdd.DtddMappingProposal;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.domain.tw.TwDefinition;
import fr.enimaloc.catapult.domain.twitch.TwitchCclDefinition;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.getter.SteamApiClient;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
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
import fr.enimaloc.catapult.service.igdb.AdminCclService;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import fr.enimaloc.catapult.service.minecraft.MinecraftFriendService;
import fr.enimaloc.catapult.service.notification.TwitchatWidgetSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChannelDashboardAssemblerTest {

    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
    private final StreamStateService streamState = mock(StreamStateService.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final AdminCclService ccls = mock(AdminCclService.class);
    private final TwDefinitionRepository tws = mock(TwDefinitionRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private final SteamApiKeyRotator keyRotator = mock(SteamApiKeyRotator.class);
    private final SteamApiClient steam = mock(SteamApiClient.class);
    private final IgdbService igdb = mock(IgdbService.class);
    private final DtddGameMappingRepository dtddMappings = mock(DtddGameMappingRepository.class);
    private final DtddGameCacheRepository dtddCache = mock(DtddGameCacheRepository.class);
    private final DtddMappingProposalRepository dtddProposals = mock(DtddMappingProposalRepository.class);
    private final OAuthTokenRepository oauthTokens = mock(OAuthTokenRepository.class);
    private final DevBackdoorResolver backdoor = mock(DevBackdoorResolver.class);
    private final MinecraftFriendService minecraft = mock(MinecraftFriendService.class);
    private final TwitchatWidgetSettingsService twitchat = mock(TwitchatWidgetSettingsService.class);

    private UserAccount owner;
    private UserAccount moderator;

    private ChannelDashboardAssembler assembler(Optional<SteamApiClient> steamClient,
                                                Optional<MinecraftFriendService> minecraftService,
                                                boolean xboxEnabled) {
        SteamProfileDiagnostics diagnostics = new SteamProfileDiagnostics(steamClient, Optional.of(keyRotator), encryption);
        ChannelDashboardAssembler assembler = new ChannelDashboardAssembler(bindings, settingsRepository, streamState,
                gameState, ccls, tws, encryption, steamClient, diagnostics, igdb, dtddMappings, dtddCache,
                dtddProposals, oauthTokens, backdoor, minecraftService, twitchat);
        ReflectionTestUtils.setField(assembler, "xboxEnabled", xboxEnabled);
        return assembler;
    }

    private ChannelDashboardAssembler assembler() {
        return assembler(Optional.of(steam), Optional.of(minecraft), true);
    }

    private static UserAccount account(String username) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchUsername(username);
        account.setTwitchId(username + "-id");
        account.setProfileImageUrl("https://img/" + username);
        return account;
    }

    private static GameBinding binding(UserAccount user, String sourceId, String name) {
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setUser(user);
        binding.setSourceId(sourceId);
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceName(name);
        binding.setStatus(GameBinding.Status.AUTO);
        binding.setTwitchGameId("tw-" + sourceId);
        binding.setTwitchGameName(name);
        return binding;
    }

    @BeforeEach
    void setUp() {
        owner = account("streamer");
        moderator = account("mod");
        TwitchCclDefinition ccl = new TwitchCclDefinition();
        ccl.setId("Gore");
        ccl.setName("Gore");
        when(ccls.getAllCcls()).thenReturn(List.of(ccl));
        TwDefinition tw = new TwDefinition();
        tw.setId("spiders");
        tw.setLabel("Spiders");
        when(tws.findAllByEnabledTrueOrderBySortOrderAscIdAsc()).thenReturn(List.of(tw));
        when(bindings.findByUser(eq(owner), any())).thenReturn(new PageImpl<>(List.of()));
        when(steam.getProfileCacheTtl()).thenReturn(Duration.ofMinutes(30));
        when(steam.getProfileStatus(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(true, false)));
        TwitchatWidgetSettings obs = new TwitchatWidgetSettings();
        obs.setEnabled(true);
        obs.setObsHost("10.0.0.2");
        obs.setObsPort(4456);
        when(twitchat.getOrCreate(owner)).thenReturn(obs);
    }

    @Nested
    class Page {
        @Test
        void describesTheChannelForItsOwner() {
            owner.setBotEnabled(true);
            when(streamState.isLive(owner)).thenReturn(true);
            UserSettings settings = new UserSettings();
            settings.setBlockedCcls(Set.of("Gore"));
            settings.setBlockedTws(Set.of("spiders"));
            when(settingsRepository.findById(owner.getId())).thenReturn(Optional.of(settings));

            ChannelPageData page = assembler().page(owner, owner, "streamer", 0, null, null);

            assertThat(page.channelUser().twitchUsername()).isEqualTo("streamer");
            assertThat(page.channelUser().profileImageUrl()).isEqualTo("https://img/streamer");
            assertThat(page.channelUsername()).isEqualTo("streamer");
            assertThat(page.isOwner()).isTrue();
            assertThat(page.isLive()).isTrue();
            assertThat(page.botEnabled()).isTrue();
            assertThat(page.availableCcls()).extracting("id").containsExactly("Gore");
            assertThat(page.availableTws()).extracting("id").containsExactly("spiders");
            assertThat(page.blockedCcls()).containsExactly("Gore");
            assertThat(page.blockedTws()).containsExactly("spiders");
            assertThat(page.obs()).isEqualTo(new ObsData(true, "10.0.0.2", 4456, false));
            assertThat(page.minecraft()).isEqualTo(new MinecraftData("NONE", null, null));
        }

        @Test
        void moderatorsDontSeeTheOwnersConnections() {
            when(bindings.findByUser(eq(owner), any())).thenReturn(new PageImpl<>(List.of()));

            ChannelPageData page = assembler().page(moderator, owner, "streamer", 0, null, null);

            assertThat(page.isOwner()).isFalse();
            assertThat(page.minecraft()).isNull();
            assertThat(page.obs()).isNull();
            assertThat(page.blockedCcls()).isEmpty();
            verifyNoInteractions(minecraft, twitchat);
        }

        @Test
        void mapsAPageOfBindings() {
            GameBinding valheim = binding(owner, "892970", "Valheim");
            valheim.setIgnored(true);
            valheim.setCcls(Set.of("Gore"));
            when(bindings.findByUser(owner, PageRequest.of(2, 20)))
                    .thenReturn(new PageImpl<>(List.of(valheim), PageRequest.of(2, 20), 41));

            var paged = assembler().page(owner, owner, "streamer", 2, null, null).bindings();

            assertThat(paged.number()).isEqualTo(2);
            assertThat(paged.totalPages()).isEqualTo(3);
            assertThat(paged.totalElements()).isEqualTo(41);
            assertThat(paged.content()).singleElement().satisfies(dto -> {
                assertThat(dto.id()).isEqualTo(valheim.getId().toString());
                assertThat(dto.status()).isEqualTo("AUTO");
                assertThat(dto.sourceType()).isEqualTo("STEAM");
                assertThat(dto.sourceName()).isEqualTo("Valheim");
                assertThat(dto.ignored()).isTrue();
                assertThat(dto.ccls()).containsExactly("Gore");
            });
        }

        @Test
        void filtersByStatusFirst_thenBySource_caseInsensitive() {
            when(bindings.findByUserAndStatus(eq(owner), eq(GameBinding.Status.MANUAL), any()))
                    .thenReturn(new PageImpl<>(List.of()));
            when(bindings.findByUserAndSourceType(eq(owner), eq(GameBinding.SourceType.XBOX), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            assembler().page(owner, owner, "streamer", 0, "manual", "steam");
            verify(bindings).findByUserAndStatus(eq(owner), eq(GameBinding.Status.MANUAL), any());

            assembler().page(owner, owner, "streamer", 0, " ", "xbox");
            verify(bindings).findByUserAndSourceType(eq(owner), eq(GameBinding.SourceType.XBOX), any());
        }

        @Test
        void currentGameCarriesTheMatchingBindingId() {
            GameBinding valheim = binding(owner, "892970", "Valheim");
            when(gameState.getLastKnownGame(owner))
                    .thenReturn(Optional.of(new DetectedGame("892970", GameBinding.SourceType.STEAM, "Valheim")));
            when(bindings.findByUserAndSourceIdAndSourceType(owner, "892970", GameBinding.SourceType.STEAM))
                    .thenReturn(Optional.of(valheim));

            assertThat(assembler().page(owner, owner, "streamer", 0, null, null).currentGame())
                    .isEqualTo(new GameDto(valheim.getId().toString(), "Valheim", "STEAM"));
        }

        @Test
        void unboundCurrentGameHasNoBindingId() {
            when(gameState.getLastKnownGame(owner))
                    .thenReturn(Optional.of(new DetectedGame("1", GameBinding.SourceType.XBOX, "Halo")));

            assertThat(assembler().page(owner, owner, "streamer", 0, null, null).currentGame())
                    .isEqualTo(new GameDto(null, "Halo", "XBOX"));
        }

        @Test
        void unavailableProvidersAreNull() {
            ChannelPageData page = assembler(Optional.empty(), Optional.empty(), false)
                    .page(owner, owner, "streamer", 0, null, null);

            assertThat(page.steam()).isNull();
            assertThat(page.xbox()).isNull();
            assertThat(page.minecraft()).isNull();
        }

        @Test
        void xboxIsConnectedOnceItsTokenIsStored() {
            when(oauthTokens.findByUserAndProvider(owner, OAuthToken.Provider.XBOX))
                    .thenReturn(Optional.of(new OAuthToken()));

            assertThat(assembler().page(owner, owner, "streamer", 0, null, null).xbox().connected()).isTrue();
        }

        @Test
        void minecraftLinkIsDescribed() {
            MinecraftServiceAccount bot = new MinecraftServiceAccount();
            bot.setMinecraftUsername("CatapultBot");
            MinecraftFriendLink link = new MinecraftFriendLink();
            link.setStatus(MinecraftFriendLink.Status.ACCEPTED);
            link.setMinecraftName("Steve");
            link.setServiceAccount(bot);
            when(minecraft.getLink(owner)).thenReturn(Optional.of(link));

            assertThat(assembler().page(owner, owner, "streamer", 0, null, null).minecraft())
                    .isEqualTo(new MinecraftData("ACCEPTED", "Steve", "CatapultBot"));
        }

        @Test
        void obsReportsAStoredPasswordWithoutSendingIt() {
            twitchat.getOrCreate(owner).setObsPasswordEncrypted("encrypted");

            assertThat(assembler().page(owner, owner, "streamer", 0, null, null).obs().hasPassword()).isTrue();
        }
    }

    @Nested
    class Steam {
        private SteamData steamFor(UserAccount viewer) {
            return assembler().page(viewer, owner, "streamer", 0, null, null).steam();
        }

        @Test
        void notConnected() {
            assertThat(steamFor(owner)).isEqualTo(new SteamData(false, false, false, false, false, false, 30));
            verify(steam, never()).getProfileStatus(any(), any());
        }

        @Test
        void connectedPublicProfile() {
            owner.setSteamId("7656");

            assertThat(steamFor(owner)).isEqualTo(new SteamData(true, false, false, false, false, false, 30));
            verify(steam).getProfileStatus("7656", null);
        }

        @Test
        void privateOrOfflineProfile() {
            owner.setSteamId("7656");
            when(steam.getProfileStatus("7656", null))
                    .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(false, true)));

            assertThat(steamFor(owner)).isEqualTo(new SteamData(true, false, false, true, false, true, 30));
        }

        @Test
        void privateProfileWhileRateLimited_isReportedAsRateLimiting() {
            owner.setSteamId("7656");
            when(steam.getProfileStatus(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(false, false)));
            when(steam.isRateLimited()).thenReturn(true);

            assertThat(steamFor(owner)).isEqualTo(new SteamData(true, false, false, false, true, false, 30));
        }

        @Test
        void privateProfileWhileEveryKeyIsBlocked_isReportedAsRateLimiting() {
            owner.setSteamId("7656");
            when(steam.getProfileStatus(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(new SteamApiClient.SteamProfileStatus(false, false)));
            when(keyRotator.isAllKeysBlocked()).thenReturn(true);

            assertThat(steamFor(owner).rateLimited()).isTrue();
        }

        @Test
        void unreachableProfile_isReportedPrivate() {
            owner.setSteamId("7656");
            when(steam.getProfileStatus(any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException()));

            assertThat(steamFor(owner).profilePrivate()).isTrue();
        }

        @Test
        void failingClient_isReportedPrivate() {
            owner.setSteamId("7656");
            when(steam.getProfileStatus(any(), any())).thenThrow(new IllegalStateException());

            assertThat(steamFor(owner).profilePrivate()).isTrue();
        }

        @Test
        void personalTokenIsDecryptedForTheProfileCall() {
            owner.setSteamId("7656");
            owner.setSteamPersonalToken("enc");
            owner.setSteamTokenShared(true);
            when(encryption.decrypt("enc")).thenReturn("KEY");

            SteamData data = steamFor(owner);

            assertThat(data.hasPersonalToken()).isTrue();
            assertThat(data.tokenShared()).isTrue();
            verify(steam).getProfileStatus("7656", "KEY");
        }

        @Test
        void moderatorsGetNoProfileDiagnostics() {
            owner.setSteamId("7656");

            assertThat(steamFor(moderator)).isEqualTo(new SteamData(true, false, false, false, false, false, 30));
            verify(steam, never()).getProfileStatus(any(), any());
        }
    }

    @Nested
    class ExampleUuid {
        private String exampleUuid() {
            return assembler().page(owner, owner, "streamer", 0, null, null).exampleUuid();
        }

        @Test
        void prefersTheEncodedCurrentGame() {
            UUID encoded = UUID.randomUUID();
            when(gameState.getLastKnownGame(owner))
                    .thenReturn(Optional.of(new DetectedGame("892970", GameBinding.SourceType.STEAM, "Valheim")));
            when(backdoor.encode(GameBinding.SourceType.STEAM, "892970")).thenReturn(Optional.of(encoded));

            assertThat(exampleUuid()).isEqualTo(encoded.toString());
        }

        @Test
        void thenTheLatestEncodableBinding() {
            UUID encoded = UUID.randomUUID();
            GameBinding latest = binding(owner, "730", "CS2");
            when(gameState.getLastKnownGame(owner))
                    .thenReturn(Optional.of(new DetectedGame("x", GameBinding.SourceType.MINECRAFT, "Minecraft")));
            when(bindings.findFirstByUserAndSourceTypeInOrderByUpdatedAtDesc(eq(owner), any()))
                    .thenReturn(Optional.of(latest));
            when(backdoor.encode(GameBinding.SourceType.STEAM, "730")).thenReturn(Optional.of(encoded));

            assertThat(exampleUuid()).isEqualTo(encoded.toString());
            verify(backdoor, never()).encode(eq(GameBinding.SourceType.MINECRAFT), any());
        }

        @Test
        void thenTheWidgetToken() {
            UUID token = UUID.randomUUID();
            owner.setWidgetToken(token);

            assertThat(exampleUuid()).isEqualTo(token.toString());
        }

        @Test
        void otherwiseTheGenericExample() {
            assertThat(exampleUuid()).isEqualTo(ApiV2.GENERIC_UUID_EXAMPLE);
        }
    }

    @Test
    void status() {
        owner.setBotEnabled(true);
        when(streamState.isLive(owner)).thenReturn(false);
        when(gameState.getLastKnownGame(owner))
                .thenReturn(Optional.of(new DetectedGame("1", GameBinding.SourceType.STEAM, "Doom")));

        StatusData status = assembler().status(moderator, owner, "Streamer");

        assertThat(status).isEqualTo(new StatusData("Streamer", false, true, false, new GameDto(null, "Doom", "STEAM")));
    }

    @Nested
    class Settings {
        @Test
        void savedSettings() {
            UserSettings saved = new UserSettings();
            saved.setUserId(owner.getId());
            saved.setCclFeatureEnabled(false);
            saved.setBlockedCcls(Set.of("Gore"));
            saved.setNoGameTwitchGameId("1");
            saved.setNoGameTwitchGameName("Just Chatting");
            saved.setApplyDefaultOnStreamEnd(true);
            saved.setTwFeatureEnabled(true);
            when(settingsRepository.findById(owner.getId())).thenReturn(Optional.of(saved));

            UserSettingsDto dto = assembler().settings(owner);

            assertThat(dto.cclFeatureEnabled()).isFalse();
            assertThat(dto.blockedCcls()).containsExactly("Gore");
            assertThat(dto.noGameTwitchGameName()).isEqualTo("Just Chatting");
            assertThat(dto.applyDefaultOnStreamEnd()).isTrue();
            assertThat(dto.twFeatureEnabled()).isTrue();
            assertThat(dto.availableCcls()).extracting("id").containsExactly("Gore");
            assertThat(dto.availableTws()).extracting("id").containsExactly("spiders");
        }

        @Test
        void defaultsWhenNeverSaved() {
            UserSettings defaults = new UserSettings();

            UserSettingsDto dto = assembler().settings(owner);

            assertThat(dto.cclFeatureEnabled()).isEqualTo(defaults.isCclFeatureEnabled());
            assertThat(dto.twFeatureEnabled()).isEqualTo(defaults.isTwFeatureEnabled());
        }
    }

    @Nested
    class DtddMapping {
        private static final DtddMappingStatusDto NONE = new DtddMappingStatusDto(null, null, false, null);

        private void playing(GameBinding.SourceType type, String id, String name) {
            when(gameState.getLastKnownGame(owner)).thenReturn(Optional.of(new DetectedGame(id, type, name)));
        }

        @Test
        void nothingWithoutACurrentGame() {
            assertThat(assembler().dtddMapping(moderator, owner)).isEqualTo(NONE);
        }

        @Test
        void nothingForGamesIgdbDoesntKnow() {
            playing(GameBinding.SourceType.XBOX, "1", "Halo");

            assertThat(assembler().dtddMapping(moderator, owner)).isEqualTo(NONE);
            verify(igdb).findByName("Halo");
        }

        @Test
        void unverifiedMappingWithoutProposals_canBeValidated() {
            playing(GameBinding.SourceType.STEAM, "892970", "Valheim");
            when(igdb.findBySteamAppId("892970")).thenReturn(Optional.of(new IgdbService.IgdbGame("42", "Valheim")));
            DtddGameMapping mapping = new DtddGameMapping();
            mapping.setIgdbId("42");
            mapping.setDtddId(7L);
            mapping.setConfidence(0.8);
            when(dtddMappings.findById("42")).thenReturn(Optional.of(mapping));
            DtddGameCache cached = new DtddGameCache();
            cached.setDtddId(7L);
            cached.setName("Valheim (2021)");
            when(dtddCache.findById(7L)).thenReturn(Optional.of(cached));

            DtddMappingStatusDto status = assembler().dtddMapping(moderator, owner);

            assertThat(status.igdbId()).isEqualTo("42");
            assertThat(status.canValidateDirectly()).isTrue();
            assertThat(status.current().dtddId()).isEqualTo(7L);
            assertThat(status.current().name()).isEqualTo("Valheim (2021)");
            assertThat(status.current().confidence()).isEqualTo(0.8);
            assertThat(status.myPendingProposal()).isNull();
        }

        @Test
        void pendingProposalsOrVerifiedMappings_cantBeValidated() {
            playing(GameBinding.SourceType.STEAM, "892970", "Valheim");
            when(igdb.findBySteamAppId("892970")).thenReturn(Optional.of(new IgdbService.IgdbGame("42", "Valheim")));
            DtddGameMapping mapping = new DtddGameMapping();
            when(dtddMappings.findById("42")).thenReturn(Optional.of(mapping));
            when(dtddProposals.countByIgdbIdAndStatus("42", DtddMappingProposal.Status.PENDING)).thenReturn(1L);
            DtddMappingProposal mine = new DtddMappingProposal();
            mine.setProposedDtddId(9L);
            mine.setReason("wrong edition");
            when(dtddProposals.findFirstByProposerAndIgdbIdAndStatus(moderator, "42", DtddMappingProposal.Status.PENDING))
                    .thenReturn(Optional.of(mine));

            DtddMappingStatusDto status = assembler().dtddMapping(moderator, owner);
            assertThat(status.canValidateDirectly()).isFalse();
            assertThat(status.current().name()).isNull();
            assertThat(status.myPendingProposal().proposedDtddId()).isEqualTo(9L);

            when(dtddProposals.countByIgdbIdAndStatus("42", DtddMappingProposal.Status.PENDING)).thenReturn(0L);
            mapping.setVerified(true);
            assertThat(assembler().dtddMapping(moderator, owner).canValidateDirectly()).isFalse();
        }

        @Test
        void unmappedGame() {
            playing(GameBinding.SourceType.STEAM, "1", "New");
            when(igdb.findBySteamAppId("1")).thenReturn(Optional.of(new IgdbService.IgdbGame("5", "New")));

            DtddMappingStatusDto status = assembler().dtddMapping(moderator, owner);

            assertThat(status).isEqualTo(new DtddMappingStatusDto(null, null, false, "5"));
        }
    }

    @Nested
    class SteamProfileCache {
        @Test
        void invalidatesTheConnectedProfile_withThePersonalToken() {
            owner.setSteamId("7656");
            owner.setSteamPersonalToken("enc");
            when(encryption.decrypt("enc")).thenReturn("KEY");

            assembler().refreshSteamProfileCache(owner);

            verify(steam).invalidateProfileCache("7656", "KEY");
        }

        @Test
        void invalidatesWithoutPersonalToken() {
            owner.setSteamId("7656");

            assembler().refreshSteamProfileCache(owner);

            verify(steam).invalidateProfileCache("7656", null);
        }

        @Test
        void nothingToDoWithoutSteam() {
            assembler().refreshSteamProfileCache(owner);
            assembler(Optional.empty(), Optional.empty(), false).refreshSteamProfileCache(owner);

            verify(steam, never()).invalidateProfileCache(any(), any());
        }
    }
}
