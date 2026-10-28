package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.notification.CatapultCategoryChangeStateService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwitchServiceTest {

    @Mock private OAuthTokenRepository oAuthTokenRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private RestClient restClient;
    @Mock private TwitchCategoryService twitchCategoryService;
    @Mock private TwitchTokenService twitchTokenService;
    @Mock private BotToggleService botToggleService;
    @Mock private CatapultCategoryChangeStateService categoryChangeStateService;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Spy
    private ExternalApiObservations apiObservations = new ExternalApiObservations(meteredRegistry(), meters);

    @Mock private RestClient.RequestBodyUriSpec patchSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersSpec headersSpec;
    @Mock private RestClient.ResponseSpec getResponseSpec;

    @InjectMocks private TwitchServiceImpl twitchService;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setTwitchId("twitch-123");

        OAuthToken token = new OAuthToken();
        token.setAccessToken("encrypted-token");

        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));
        when(userAccountRepository.findById(any())).thenReturn(Optional.of(user));
        when(tokenEncryptionService.decrypt("encrypted-token")).thenReturn("plain-token");
        when(twitchTokenService.resolveAccessToken(any(OAuthToken.class), eq(user))).thenReturn("plain-token");
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn(null);

        UserSettings settings = new UserSettings();
        settings.setCclFeatureEnabled(true);
        when(userSettingsRepository.findById(any())).thenReturn(Optional.of(settings));

        ReflectionTestUtils.setField(twitchService, "twitchClientId", "test-client-id");

        doReturn(patchSpec).when(restClient).patch();
        doReturn(bodySpec).when(patchSpec).uri(anyString());
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        doReturn(responseSpec).when(bodySpec).retrieve();

        doReturn(getSpec).when(restClient).get();
        doReturn(headersSpec).when(getSpec).uri(anyString(), any(Object[].class));
        doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
        doReturn(getResponseSpec).when(headersSpec).retrieve();
    }

    /** The registry the observations go through: each one ends up as a timer in {@link #meters}. */
    private ObservationRegistry meteredRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return registry;
    }

    private GameBinding binding(GameBinding.Status status, boolean ignored, boolean cclEnabled, Set<String> ccls) {
        GameBinding b = new GameBinding();
        b.setStatus(status);
        b.setIgnored(ignored);
        b.setTwitchGameId("game-456");
        b.setUser(user);
        b.setCcls(ccls);
        b.setCclEnabled(cclEnabled);
        return b;
    }

    @Test
    void updateChannel_skipsIncompleteBinding() {
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.INCOMPLETE, false, true, Set.of()));

        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannel_skipsIgnoredBinding() {
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, true, true, Set.of()));

        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannel_skipsWhenNoTwitchToken() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannel_withEmptyCcls_sendsAllEditableAsDisabled() {
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        List<Map<String, Object>> cclPayload =
            (List<Map<String, Object>>) bodyCaptor.getValue().get("content_classification_labels");

        assertThat(cclPayload).isNotNull();
        assertThat(cclPayload).allSatisfy(e -> assertThat(e.get("is_enabled")).isEqualTo(false));
        assertThat(cclPayload.stream().map(e -> (String) e.get("id")).toList())
            .containsExactlyInAnyOrder("ViolentGraphic", "SexualThemes", "DrugsIntoxication", "Gambling", "ProfanityVulgarity");
    }

    @Test
    void updateChannel_withSomeCcls_enablesOnlyMatching() {
        twitchService.updateChannelReporting(user,
            binding(GameBinding.Status.AUTO, false, true, Set.of("ViolentGraphic", "Gambling")));

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        List<Map<String, Object>> cclPayload =
            (List<Map<String, Object>>) bodyCaptor.getValue().get("content_classification_labels");

        var enabled = cclPayload.stream().filter(e -> Boolean.TRUE.equals(e.get("is_enabled")))
            .map(e -> (String) e.get("id")).toList();
        var disabled = cclPayload.stream().filter(e -> Boolean.FALSE.equals(e.get("is_enabled")))
            .map(e -> (String) e.get("id")).toList();

        assertThat(enabled).containsExactlyInAnyOrder("ViolentGraphic", "Gambling");
        assertThat(disabled).containsExactlyInAnyOrder("SexualThemes", "DrugsIntoxication", "ProfanityVulgarity");
    }

    @Test
    void updateChannel_matureGameNotInCclPayload() {
        twitchService.updateChannelReporting(user,
            binding(GameBinding.Status.AUTO, false, true, Set.of("MatureGame")));

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        List<Map<String, Object>> cclPayload =
            (List<Map<String, Object>>) bodyCaptor.getValue().get("content_classification_labels");

        assertThat(cclPayload.stream().map(e -> (String) e.get("id")).toList())
            .doesNotContain("MatureGame");
    }

    @Test
    void updateChannel_cclDisabledOnBinding_doesNotSendCclPayload() {
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, false, Set.of("ViolentGraphic")));

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        assertThat(bodyCaptor.getValue()).doesNotContainKey("content_classification_labels");
    }

    @Test
    void updateChannel_on401_refreshFails_disablesBot() {
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        assertThat(user.isBotEnabled()).isTrue(); // pre-condition
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verify(botToggleService).pauseAfterATokenFailure(user);
    }

    @Test
    void updateChannel_successfulPatch_recordsSelfSetStateWithoutNotifying() {
        GameBinding binding = binding(GameBinding.Status.AUTO, false, true, Set.of());
        binding.setTwitchGameId("222");
        binding.setTwitchGameName("New Game");

        twitchService.updateChannelReporting(user, binding);

        // The Twitchat dispatcher is not a collaborator of TwitchServiceImpl: the notification
        // is emitted once, from TwitchEventSubService's channel.update handler.
        verify(categoryChangeStateService).recordCatapultChangeAndReturnPrevious(user, "222");
    }

    @Test
    void setCategory_successfulPatch_recordsSelfSetState() {
        twitchService.setCategory(user, "12345", "Some Game");

        verify(categoryChangeStateService).recordCatapultChangeAndReturnPrevious(user, "12345");
    }

    @Test
    void setCategory_botActive_patchesOnlyGameId() {
        assertThat(twitchService.setCategory(user, "12345", "Some Game")).isEqualTo(ChannelUpdateOutcome.APPLIED);

        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(patchSpec).uri(uriCaptor.capture());
        assertThat(uriCaptor.getValue()).contains("/channels?broadcaster_id=" + user.getTwitchId());

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isEqualTo(Map.of("game_id", "12345"));
    }

    @Test
    void setCategory_botDisabled_skipsPatch() {
        user.setBotEnabled(false);

        assertThat(twitchService.setCategory(user, "12345", "Some Game")).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verifyNoInteractions(restClient, oAuthTokenRepository);
    }

    @Test
    void updateChannel_botDisabled_doesNotCallHelix() {
        user.setBotEnabled(false);

        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verifyNoInteractions(restClient, oAuthTokenRepository);
    }

    @Test
    void updateChannel_accountInactive_doesNotCallHelix() {
        user.setStatus(UserAccount.Status.INACTIVE);

        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verifyNoInteractions(restClient, oAuthTokenRepository);
    }

    @Test
    void updateChannel_staleInMemoryUserStillEnabled_butDbSaysDisabled_skipsHelix() {
        // Simulates a caller (e.g. TwitchEventSubService's long-lived listener, or a
        // SchedulerService poll cycle) holding a UserAccount snapshot taken before the bot
        // was disabled elsewhere — the DB is the source of truth, not the in-memory flag.
        UserAccount staleUser = new UserAccount();
        staleUser.setId(user.getId());
        staleUser.setTwitchId(user.getTwitchId());
        staleUser.setBotEnabled(true);
        when(userAccountRepository.findById(staleUser.getId())).thenReturn(Optional.of(user));
        user.setBotEnabled(false);

        twitchService.updateChannelReporting(staleUser, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        assertThat(staleUser.isBotEnabled()).isTrue(); // the stale flag itself never flips
        verifyNoInteractions(restClient, oAuthTokenRepository);
    }

    @Test
    void resetToDefault_noSettings_doesNotCallApi() {
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verify(restClient, never()).patch();
    }

    @Test
    void resetToDefault_noGameIdConfigured_doesNotCallApi() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId(null);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verify(restClient, never()).patch();
    }

    @Test
    void resetToDefault_gameIdConfigured_patchesTwitchChannel() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");

        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.APPLIED);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).containsEntry("game_id", "12345");
    }

    @Test
    void resetToDefault_withCcls_includesCclPayloadInPatch() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        settings.setCclFeatureEnabled(true);
        settings.setNoGameCcls(Set.of("ViolentGraphic", "Gambling"));

        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        twitchService.resetToDefault(user);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        List<Map<String, Object>> cclPayload =
            (List<Map<String, Object>>) bodyCaptor.getValue().get("content_classification_labels");

        assertThat(cclPayload).isNotNull();
        var enabled = cclPayload.stream()
            .filter(e -> Boolean.TRUE.equals(e.get("is_enabled")))
            .map(e -> (String) e.get("id")).toList();
        assertThat(enabled).containsExactlyInAnyOrder("ViolentGraphic", "Gambling");
    }

    @Test
    void resetToDefault_withCcls_butCclFeatureDisabled_doesNotSendCclPayload() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        settings.setCclFeatureEnabled(false);
        settings.setNoGameCcls(Set.of("ViolentGraphic"));

        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        twitchService.resetToDefault(user);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        assertThat(bodyCaptor.getValue()).doesNotContainKey("content_classification_labels");
    }

    @Test
    void findCategoryIdByName_userTokenPresent_returnsIdFromExactMatch() {
        doReturn(Map.of("data", List.of(Map.of("id", "999", "name", "Scribble It!"))))
            .when(getResponseSpec).body(Map.class);

        Optional<String> result = twitchService.findCategoryIdByName(user, "Scribble It!");

        assertThat(result).contains("999");
        verify(twitchCategoryService, never()).searchCategories(anyString());
    }

    @Test
    void findCategoryIdByName_userTokenAbsent_fallsBackToSearchCategories() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());
        when(twitchCategoryService.searchCategories("Scribble It!"))
            .thenReturn(List.of(new TwitchCategory("999", "Scribble It!", "https://img/s.jpg")));

        Optional<String> result = twitchService.findCategoryIdByName(user, "Scribble It!");

        assertThat(result).contains("999");
        verify(restClient, never()).get();
    }

    @Test
    void findCategoryIdByName_userTokenPresentButNoMatch_fallsBackToSearchCategories() {
        doReturn(Map.of("data", List.of())).when(getResponseSpec).body(Map.class);
        when(twitchCategoryService.searchCategories("Scribble It!"))
            .thenReturn(List.of(new TwitchCategory("999", "Scribble It!", "https://img/s.jpg")));

        Optional<String> result = twitchService.findCategoryIdByName(user, "Scribble It!");

        assertThat(result).contains("999");
    }

    @Test
    void findCategoryIdByName_igdbNameDiffersFromTwitchByPunctuation_matchesViaSearch() {
        // IGDB returns "Scribble It" (no !), Twitch has "Scribble It!" — normalize should bridge them
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());
        when(twitchCategoryService.searchCategories("Scribble It"))
            .thenReturn(List.of(new TwitchCategory("999", "Scribble It!", "https://img/s.jpg")));

        Optional<String> result = twitchService.findCategoryIdByName(user, "Scribble It");

        assertThat(result).contains("999");
    }

    @Test
    void findCategoryIdByName_bothPathsEmpty_returnsEmpty() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());
        when(twitchCategoryService.searchCategories(anyString())).thenReturn(List.of());

        Optional<String> result = twitchService.findCategoryIdByName(user, "Unknown Game");

        assertThat(result).isEmpty();
    }

    @Test
    void resetToDefault_withEmptyCcls_doesNotSendCclPayload() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        settings.setCclFeatureEnabled(true);
        settings.setNoGameCcls(Set.of());

        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(restClient.patch()).thenReturn(patchSpec);
        when(patchSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        twitchService.resetToDefault(user);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        assertThat(bodyCaptor.getValue()).doesNotContainKey("content_classification_labels");
    }

    @Test
    void getModeratedChannelIds_returns_broadcaster_ids_from_twitch_api() {
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        doReturn(headersSpec).when(getSpec).uri(uriCaptor.capture());

        TwitchServiceImpl.ModeratedChannelsResponse mockResponse =
            new TwitchServiceImpl.ModeratedChannelsResponse(List.of(
                new TwitchServiceImpl.ModeratedChannel("ch1"),
                new TwitchServiceImpl.ModeratedChannel("ch2")
            ));
        doReturn(mockResponse).when(getResponseSpec).body(TwitchServiceImpl.ModeratedChannelsResponse.class);

        List<String> result = twitchService.getModeratedChannelIds(user);

        assertThat(result).containsExactlyInAnyOrder("ch1", "ch2");
        assertThat(uriCaptor.getValue())
            .contains("/moderation/channels")
            .contains("user_id=" + user.getTwitchId());
    }

    @Test
    void getModeratedChannelIds_returns_empty_list_when_api_throws() {
        doReturn(headersSpec).when(getSpec).uri(anyString());
        doThrow(new RuntimeException("network error")).when(getResponseSpec)
            .body(TwitchServiceImpl.ModeratedChannelsResponse.class);

        List<String> result = twitchService.getModeratedChannelIds(user);

        assertThat(result).isEmpty();
    }

    @Test
    void getModeratedChannelIds_returns_empty_list_when_no_token() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        List<String> result = twitchService.getModeratedChannelIds(user);

        assertThat(result).isEmpty();
    }

    @Test
    void updateChannel_on401_refreshSucceeds_retriesAndUpdatesChannel() {
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        // First call (plain-token) → 401, second call (refreshed-token) → success
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .doReturn(null)
            .when(responseSpec).toBodilessEntity();

        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verifyNoInteractions(botToggleService);
    }

    @Test
    void updateChannel_on401_refreshSucceeds_retryAlso401_disablesBot() {
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .when(responseSpec).toBodilessEntity();

        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verify(botToggleService).pauseAfterATokenFailure(user);
    }

    @Test
    void updateChannelReporting_twitchAccepts_isApplied() {
        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.APPLIED);
    }

    @Test
    void updateChannelReporting_appliedAfterTokenRefresh() {
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .doReturn(null)
            .when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.APPLIED);
    }

    @Test
    void updateChannelReporting_twitchError_isFailed() {
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);
    }

    @Test
    void updateChannelReporting_rateLimited_isFailed() {
        doThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);
    }

    @Test
    void updateChannelReporting_tokenRefreshFails_isFailed() {
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);
    }

    @Test
    void updateChannelReporting_unexpectedError_isFailed() {
        doThrow(new IllegalStateException("network down")).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);
    }

    @Test
    void updateChannelReporting_noTwitchToken_isFailed() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);
        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannelReporting_botDisabled_isSkipped() {
        user.setBotEnabled(false);

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.SKIPPED);
        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannelReporting_incompleteBinding_isSkipped() {
        assertThat(twitchService.updateChannelReporting(user,
                binding(GameBinding.Status.INCOMPLETE, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.SKIPPED);
        verifyNoInteractions(restClient);
    }

    @Test
    void resetToDefault_botDisabled_doesNotCallHelix() {
        user.setBotEnabled(false);

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.SKIPPED);

        verifyNoInteractions(restClient, userSettingsRepository);
    }

    @Test
    void resetToDefault_accountInactive_doesNotCallHelix() {
        user.setStatus(UserAccount.Status.INACTIVE);

        twitchService.resetToDefault(user);

        verifyNoInteractions(restClient, userSettingsRepository);
    }

    @Test
    void resetToDefault_staleInMemoryUserStillEnabled_butDbSaysDisabled_skipsHelix() {
        UserAccount staleUser = new UserAccount();
        staleUser.setId(user.getId());
        staleUser.setTwitchId(user.getTwitchId());
        staleUser.setBotEnabled(true);
        when(userAccountRepository.findById(staleUser.getId())).thenReturn(Optional.of(user));
        user.setBotEnabled(false);

        twitchService.resetToDefault(staleUser);

        assertThat(staleUser.isBotEnabled()).isTrue();
        verifyNoInteractions(restClient, userSettingsRepository);
    }

    @Test
    void resetToDefault_on401_refreshFails_disablesBot() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn(null);
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        twitchService.resetToDefault(user);

        verify(botToggleService).pauseAfterATokenFailure(user);
    }

    @Test
    void resetToDefault_on401_refreshSucceeds_retriesAndResets() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .doReturn(null)
            .when(responseSpec).toBodilessEntity();

        twitchService.resetToDefault(user);

        verifyNoInteractions(botToggleService);
    }

    // ---- Pins of today's Twitch layer, before the category chain moves (see CategoryBehaviourTest) ----

    @Test
    void setCategory_on401_refreshesAndRetries() {
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .doReturn(null)
            .when(responseSpec).toBodilessEntity();

        assertThat(twitchService.setCategory(user, "12345", "Some Game")).isEqualTo(ChannelUpdateOutcome.APPLIED);

        verify(twitchTokenService).refreshAccessToken(any(OAuthToken.class), eq(user));
        verify(bodySpec).header(TwitchServiceImpl.AUTHORIZATION, TwitchServiceImpl.AUTHORIZATION_BEARER + "refreshed-token");
        verifyNoInteractions(botToggleService);
        verify(categoryChangeStateService, times(1)).recordCatapultChangeAndReturnPrevious(user, "12345");
        assertThat(timer("set_category", "success")).isNotNull();
    }

    @Test
    void setCategory_on401_refreshFails_pausesTheBotAndThrows() {
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        assertThrows(HttpClientErrorException.class, () -> twitchService.setCategory(user, "12345", "Some Game"));

        verify(twitchTokenService).refreshAccessToken(any(OAuthToken.class), eq(user));
        verify(botToggleService).pauseAfterATokenFailure(user);
        verify(categoryChangeStateService, never()).recordCatapultChangeAndReturnPrevious(any(), any());
        assertThat(timer("set_category", "error")).isNotNull();
    }

    @Test
    void setCategory_on401_retryFails_pausesTheBotAndThrows() {
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .doThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY))
            .when(responseSpec).toBodilessEntity();

        assertThrows(HttpServerErrorException.class, () -> twitchService.setCategory(user, "12345", "Some Game"));

        verify(botToggleService).pauseAfterATokenFailure(user);
        verify(categoryChangeStateService, never()).recordCatapultChangeAndReturnPrevious(any(), any());
    }

    @Test
    void setCategory_otherHttpError_throwsWithoutRefreshing() {
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST)).when(responseSpec).toBodilessEntity();

        assertThrows(HttpClientErrorException.class, () -> twitchService.setCategory(user, "12345", "Some Game"));

        verify(twitchTokenService, never()).refreshAccessToken(any(), any());
        verifyNoInteractions(botToggleService);
    }

    @Test
    void setCategory_withoutToken_recordsNoMarker() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        assertThat(twitchService.setCategory(user, "12345", "Some Game")).isEqualTo(ChannelUpdateOutcome.FAILED);

        verify(restClient, never()).patch();
        verifyNoInteractions(categoryChangeStateService);
    }

    @Test
    void resetToDefault_recordsTheMarker() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        twitchService.resetToDefault(user);

        verify(bodySpec).body(any(Map.class));
        verify(categoryChangeStateService, times(1)).recordCatapultChangeAndReturnPrevious(user, "12345");
        assertThat(timer("reset_to_default", "success")).isNotNull();
    }

    @Test
    void resetToDefault_afterATokenRefresh_recordsTheMarker() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(twitchTokenService.refreshAccessToken(any(OAuthToken.class), eq(user))).thenReturn("refreshed-token");
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
            .doReturn(null)
            .when(responseSpec).toBodilessEntity();

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.APPLIED);

        verify(categoryChangeStateService, times(1)).recordCatapultChangeAndReturnPrevious(user, "12345");
    }

    @Test
    void resetToDefault_twitchError_recordsNoMarker() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        doThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.FAILED);

        verifyNoInteractions(categoryChangeStateService);
    }

    @Test
    void resetToDefault_on401_refreshFails_pausesTheBot() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId("12345");
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.FAILED);

        verify(twitchTokenService).refreshAccessToken(any(OAuthToken.class), eq(user));
        verify(botToggleService).pauseAfterATokenFailure(user);
        verifyNoInteractions(categoryChangeStateService);
    }

    @Test
    void updateChannel_syntheticBinding_ignoresTheBlocklist() {
        UserSettings settings = new UserSettings();
        settings.setBlockedCcls(new java.util.HashSet<>(Set.of("Gambling")));
        when(userSettingsRepository.findById(any())).thenReturn(Optional.of(settings));
        GameBinding fallback = binding(GameBinding.Status.MANUAL, false, true, Set.of("Gambling"));
        fallback.setId(null);

        twitchService.updateChannelReporting(user, fallback);

        assertThat(cclEnabled("Gambling")).isTrue();
    }

    @Test
    void updateChannel_persistedBinding_appliesTheBlocklist() {
        UserSettings settings = new UserSettings();
        settings.setBlockedCcls(new java.util.HashSet<>(Set.of("Gambling")));
        when(userSettingsRepository.findById(any())).thenReturn(Optional.of(settings));
        GameBinding saved = binding(GameBinding.Status.AUTO, false, true, Set.of("Gambling"));
        saved.setId(java.util.UUID.randomUUID());

        twitchService.updateChannelReporting(user, saved);

        assertThat(cclEnabled("Gambling")).isFalse();
    }

    @Test
    void updateChannel_recordsTheMarkerOnce() {
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verify(categoryChangeStateService, times(1)).recordCatapultChangeAndReturnPrevious(user, "game-456");
    }

    @Test
    void updateChannel_twitchError_isObservedAsAnError() {
        doThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);

        assertThat(timer("update_channel", "success")).isNull();
        assertThat(meters.find(ExternalApiObservations.METRIC).tags("api", "twitch", "operation", "update_channel",
            "outcome", "error", "error", "HttpServerErrorException", "status", "500").timer()).isNotNull();
    }

    @Test
    void updateChannel_rateLimited_isObservedAsAnError() {
        doThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);

        assertThat(meters.find(ExternalApiObservations.METRIC).tags("api", "twitch", "operation", "update_channel",
            "outcome", "error", "status", "429").timer()).isNotNull();
    }

    @Test
    void updateChannel_on401_refreshFails_isObservedAsAnError() {
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);

        verify(botToggleService).pauseAfterATokenFailure(user);
        assertThat(meters.find(ExternalApiObservations.METRIC).tags("api", "twitch", "operation", "update_channel",
            "outcome", "error", "error", "HttpClientErrorException", "status", "401").timer()).isNotNull();
    }

    @Test
    void updateChannel_unexpectedError_isObservedAsAnError_andNotThrown() {
        doThrow(new IllegalStateException("network down")).when(responseSpec).toBodilessEntity();

        assertThat(twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())))
            .isEqualTo(ChannelUpdateOutcome.FAILED);

        assertThat(timer("update_channel", "error")).isNotNull();
    }

    @Test
    void updateChannel_accepted_isObservedAsASuccess() {
        twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        assertThat(timer("update_channel", "success")).isNotNull();
        assertThat(timer("update_channel", "error")).isNull();
    }

    @Test
    void updateChannel_repositoryFailure_stillPropagates() {
        when(userSettingsRepository.findById(any())).thenThrow(new IllegalStateException("database down"));

        assertThrows(IllegalStateException.class, () ->
            twitchService.updateChannelReporting(user, binding(GameBinding.Status.AUTO, false, true, Set.of())));
    }

    @Test
    void setCategory_twitchError_isObservedAsAnError() {
        doThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)).when(responseSpec).toBodilessEntity();

        assertThrows(HttpServerErrorException.class, () -> twitchService.setCategory(user, "12345", "Some Game"));

        assertThat(timer("set_category", "error")).isNotNull();
        assertThat(timer("set_category", "success")).isNull();
    }

    /** Whether the PATCH sent {@code ccl} enabled. */
    private boolean cclEnabled(String ccl) {
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());
        List<Map<String, Object>> cclPayload =
            (List<Map<String, Object>>) bodyCaptor.getValue().get("content_classification_labels");
        return cclPayload.stream().filter(e -> ccl.equals(e.get("id")))
            .map(e -> (Boolean) e.get("is_enabled")).findFirst().orElseThrow();
    }

    private Timer timer(String operation, String outcome) {
        return meters.find(ExternalApiObservations.METRIC)
            .tags("api", "twitch", "operation", operation, "outcome", outcome).timer();
    }
}
