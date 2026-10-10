package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatWidgetConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatWidgetSettings;
import fr.enimaloc.catapult.repository.twitchat.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.account.WidgetTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatWidgetSettingsServiceTest {

    @Mock private TwitchatWidgetSettingsRepository repository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @Mock private WidgetTokenService widgetTokenService;
    @InjectMocks private TwitchatWidgetSettingsService service;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getOrCreate_noExistingSettings_createsDisabledAndEnsuresToken() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings settings = service.getOrCreate(user);

        assertThat(settings.isEnabled()).isFalse();
        assertThat(settings.getUser()).isEqualTo(user);
        verify(widgetTokenService).getOrCreate(user);
        verify(repository).save(settings);
    }

    @Test
    void getOrCreate_existingSettings_returnsWithoutSaving() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));

        TwitchatWidgetSettings result = service.getOrCreate(user);

        assertThat(result).isSameAs(existing);
        verify(widgetTokenService).getOrCreate(user);
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_withNewPassword_encryptsAndSaves() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(tokenEncryptionService.encrypt("s3cret")).thenReturn("ENC(s3cret)");
        when(tokenEncryptionService.decrypt("ENC(s3cret)")).thenReturn("s3cret");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings result = service.updateSettings(user, true, "127.0.0.1", 4455, "s3cret");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getObsHost()).isEqualTo("127.0.0.1");
        assertThat(result.getObsPort()).isEqualTo(4455);
        assertThat(result.getObsPasswordEncrypted()).isEqualTo("ENC(s3cret)");
    }

    @Test
    void updateSettings_publishesLiveConfigForAnAlreadyOpenWidget() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(tokenEncryptionService.encrypt("s3cret")).thenReturn("ENC(s3cret)");
        when(tokenEncryptionService.decrypt("ENC(s3cret)")).thenReturn("s3cret");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateSettings(user, true, "127.0.0.1", 4455, "s3cret");

        ArgumentCaptor<TwitchatWidgetConfig> captor = ArgumentCaptor.forClass(TwitchatWidgetConfig.class);
        verify(channelEventPublisher).twitchatWidgetSettingsUpdated(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().obsHost()).isEqualTo("127.0.0.1");
        assertThat(captor.getValue().obsPort()).isEqualTo(4455);
        assertThat(captor.getValue().obsPassword()).isEqualTo("s3cret");
    }

    @Test
    void newSettings_detectTheTwitchatBranch() {
        assertThat(new TwitchatWidgetSettings().getTwitchatBranch()).isEqualTo("auto");
    }

    @Test
    void updateBranch_savesAndPublishesIt() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings result = service.updateBranch(user, "beta");

        assertThat(result.getTwitchatBranch()).isEqualTo("beta");
        ArgumentCaptor<TwitchatWidgetConfig> captor = ArgumentCaptor.forClass(TwitchatWidgetConfig.class);
        verify(channelEventPublisher).twitchatWidgetSettingsUpdated(eq(user.getId()), captor.capture());
        assertThat(captor.getValue().twitchatBranch()).isEqualTo("beta");
    }

    @Test
    void updateBranch_unknownBranch_rejectedWithoutSaving() {
        assertThatThrownBy(() -> service.updateBranch(user, "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown Twitchat branch main");
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_withNullPassword_keepsExistingEncryptedPassword() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        existing.setObsPasswordEncrypted("ENC(old)");
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(tokenEncryptionService.decrypt("ENC(old)")).thenReturn("old");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings result = service.updateSettings(user, true, "127.0.0.1", 4455, null);

        assertThat(result.getObsPasswordEncrypted()).isEqualTo("ENC(old)");
        verify(tokenEncryptionService, never()).encrypt(any());
    }

    @Test
    void updateSettings_blankHostAndNullPort_fallsBackToPlaceholderDefaults() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings result = service.updateSettings(user, true, "  ", null, null);

        assertThat(result.getObsHost()).isEqualTo("127.0.0.1");
        assertThat(result.getObsPort()).isEqualTo(4455);
    }

    @Test
    void regenerateToken_doesNotPublishLiveConfig() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));

        service.regenerateToken(user);

        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void regenerateToken_delegatesToWidgetTokenService() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));

        TwitchatWidgetSettings result = service.regenerateToken(user);

        assertThat(result).isSameAs(existing);
        verify(widgetTokenService).regenerate(user);
    }

    @Test
    void enabledConfig_enabled_returnsTheDecryptedConnection() {
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setEnabled(true);
        settings.setObsHost("10.0.0.2");
        settings.setObsPort(4456);
        settings.setObsPasswordEncrypted("enc");
        when(repository.findById(user.getId())).thenReturn(Optional.of(settings));
        when(tokenEncryptionService.decrypt("enc")).thenReturn("pw");

        assertThat(service.enabledConfig(user.getId())).contains(new TwitchatWidgetConfig("10.0.0.2", 4456, "pw", "auto"));
    }

    @Test
    void enabledConfig_disabledOrMissing_isEmpty() {
        TwitchatWidgetSettings settings = new TwitchatWidgetSettings();
        settings.setEnabled(false);
        when(repository.findById(user.getId())).thenReturn(Optional.of(settings));
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.enabledConfig(user.getId())).isEmpty();
        assertThat(service.enabledConfig(unknown)).isEmpty();
    }
}
