package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatWidgetSettings;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.TwitchatWidgetSettingsRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.notification.dto.TwitchatWidgetConfig;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatWidgetSettingsServiceTest {

    @Mock private TwitchatWidgetSettingsRepository repository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private ChannelEventPublisher channelEventPublisher;
    @InjectMocks private TwitchatWidgetSettingsService service;

    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
    }

    @Test
    void getOrCreate_noExistingSettings_createsDisabledWithFreshToken() {
        when(repository.findById(user.getId())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings settings = service.getOrCreate(user);

        assertThat(settings.isEnabled()).isFalse();
        assertThat(settings.getWidgetToken()).isNotNull();
        assertThat(settings.getUser()).isEqualTo(user);
        verify(repository).save(settings);
    }

    @Test
    void getOrCreate_existingSettings_returnsWithoutSaving() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        existing.setWidgetToken(UUID.randomUUID());
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));

        TwitchatWidgetSettings result = service.getOrCreate(user);

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_withNewPassword_encryptsAndSaves() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        existing.setWidgetToken(UUID.randomUUID());
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
        existing.setWidgetToken(UUID.randomUUID());
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
    void updateSettings_withNullPassword_keepsExistingEncryptedPassword() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        existing.setWidgetToken(UUID.randomUUID());
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
        existing.setWidgetToken(UUID.randomUUID());
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
        existing.setWidgetToken(UUID.randomUUID());
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.regenerateToken(user);

        verifyNoInteractions(channelEventPublisher);
    }

    @Test
    void regenerateToken_replacesTokenAndSaves() {
        TwitchatWidgetSettings existing = new TwitchatWidgetSettings();
        existing.setUser(user);
        UUID oldToken = UUID.randomUUID();
        existing.setWidgetToken(oldToken);
        when(repository.findById(user.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TwitchatWidgetSettings result = service.regenerateToken(user);

        assertThat(result.getWidgetToken()).isNotEqualTo(oldToken);
        verify(repository).save(existing);
    }
}
