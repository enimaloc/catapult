package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.UserSettings;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.repository.UserSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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

    @Mock private RestClient.RequestBodyUriSpec patchSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private TwitchService twitchService;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setTwitchId("twitch-123");

        OAuthToken token = new OAuthToken();
        token.setAccessToken("encrypted-token");

        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));
        when(tokenEncryptionService.decrypt("encrypted-token")).thenReturn("plain-token");

        UserSettings settings = new UserSettings();
        settings.setCclFeatureEnabled(true);
        when(userSettingsRepository.findById(any())).thenReturn(Optional.of(settings));

        ReflectionTestUtils.setField(twitchService, "twitchClientId", "test-client-id");

        doReturn(patchSpec).when(restClient).patch();
        doReturn(bodySpec).when(patchSpec).uri(anyString());
        doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
        doReturn(bodySpec).when(bodySpec).body(any(Map.class));
        doReturn(responseSpec).when(bodySpec).retrieve();
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
        twitchService.updateChannel(user, binding(GameBinding.Status.INCOMPLETE, false, true, Set.of()));

        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannel_skipsIgnoredBinding() {
        twitchService.updateChannel(user, binding(GameBinding.Status.AUTO, true, true, Set.of()));

        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannel_skipsWhenNoTwitchToken() {
        when(oAuthTokenRepository.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.empty());

        twitchService.updateChannel(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        verifyNoInteractions(restClient);
    }

    @Test
    void updateChannel_withEmptyCcls_sendsAllEditableAsDisabled() {
        twitchService.updateChannel(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

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
        twitchService.updateChannel(user,
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
        twitchService.updateChannel(user,
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
        twitchService.updateChannel(user, binding(GameBinding.Status.AUTO, false, false, Set.of("ViolentGraphic")));

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());

        assertThat(bodyCaptor.getValue()).doesNotContainKey("content_classification_labels");
    }

    @Test
    void updateChannel_on401_disablesBotAndSavesUser() {
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)).when(responseSpec).toBodilessEntity();

        assertThat(user.isBotEnabled()).isTrue(); // pre-condition
        twitchService.updateChannel(user, binding(GameBinding.Status.AUTO, false, true, Set.of()));

        assertThat(user.isBotEnabled()).isFalse();
        verify(userAccountRepository).save(user);
    }

    @Test
    void resetToDefault_noSettings_doesNotCallApi() {
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.empty());

        twitchService.resetToDefault(user);

        verify(restClient, never()).patch();
    }

    @Test
    void resetToDefault_noGameIdConfigured_doesNotCallApi() {
        UserSettings settings = new UserSettings();
        settings.setNoGameTwitchGameId(null);
        when(userSettingsRepository.findById(user.getId())).thenReturn(Optional.of(settings));

        twitchService.resetToDefault(user);

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

        twitchService.resetToDefault(user);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).containsEntry("game_id", "12345");
    }
}
