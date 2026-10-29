package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelSettingsServiceTest {

    private final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
    private final ChannelEventPublisher publisher = mock(ChannelEventPublisher.class);
    private final ExtensionLayoutApplier layouts = mock(ExtensionLayoutApplier.class);
    private final ChannelSettingsService service = new ChannelSettingsService(settingsRepository,
            mock(CategoryChain.class), mock(RaidRestoreGateService.class), publisher, layouts);

    private final Set<ExtensionSlotSetting> layout =
            Set.of(new ExtensionSlotSetting(TwitchExtensionsDto.Type.PANEL, 1, "p1", "Panel"));
    private UserAccount channel;
    private UserSettings settings;

    @BeforeEach
    void setUp() {
        channel = new UserAccount();
        channel.setId(UUID.randomUUID());
        settings = new UserSettings();
        settings.setUserId(channel.getId());
        settings.getNoGameExtensionSlots().add(new ExtensionSlotSetting(TwitchExtensionsDto.Type.OVERLAY, 1, "old", "Old"));
        when(settingsRepository.findById(channel.getId())).thenReturn(Optional.of(settings));
    }

    @Test
    void saveNoGameExtensions_replacesTheLayout_andReappliesItWhenCurrent() {
        service.saveNoGameExtensions(channel, layout);

        assertThat(settings.getNoGameExtensionSlots()).isEqualTo(layout);
        verify(settingsRepository).save(settings);
        verify(layouts).applyIfCurrent(channel, ExtensionLayoutApplier.NO_GAME_KEY, layout);
        verify(publisher).settingsUpdated(channel.getId(), settings);
    }

    @Test
    void saveIncompleteFallbackExtensions_replacesTheLayout_andReappliesItWhenCurrent() {
        service.saveIncompleteFallbackExtensions(channel, layout);

        assertThat(settings.getIncompleteFallbackExtensionSlots()).isEqualTo(layout);
        verify(settingsRepository).save(settings);
        verify(layouts).applyIfCurrent(channel, ExtensionLayoutApplier.INCOMPLETE_KEY, layout);
        verify(publisher).settingsUpdated(channel.getId(), settings);
    }
}
