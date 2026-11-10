package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.TitleSettingsRequest;
import fr.enimaloc.catapult.common.dto.channel.TwitchExtensionsDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.ExtensionSlotSetting;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.category.ExtensionLayoutApplier;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelSettingsServiceTest {

    private final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
    private final ChannelEventPublisher publisher = mock(ChannelEventPublisher.class);
    private final ExtensionLayoutApplier layouts = mock(ExtensionLayoutApplier.class);
    private final StreamTitleService streamTitle = mock(StreamTitleService.class);
    private final ChannelSettingsService service = new ChannelSettingsService(settingsRepository,
            mock(CategoryChain.class), mock(RaidRestoreGateService.class), publisher, layouts, streamTitle);

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

    @Test
    void saveTitle_storesTheSwitchAndTheTrimmedTemplate() {
        when(streamTitle.isAvailableFor(channel)).thenReturn(true);

        service.saveTitle(channel, new TitleSettingsRequest(true, "  Playing {{GAME}}  "));

        assertThat(settings.isTitleFeatureEnabled()).isTrue();
        assertThat(settings.getTitleTemplate()).isEqualTo("Playing {{GAME}}");
        verify(publisher).settingsUpdated(channel.getId(), settings);
    }

    @Test
    void saveTitle_aBlankTemplateIsNone() {
        when(streamTitle.isAvailableFor(channel)).thenReturn(true);
        settings.setTitleTemplate("old");

        service.saveTitle(channel, new TitleSettingsRequest(false, "   "));

        assertThat(settings.isTitleFeatureEnabled()).isFalse();
        assertThat(settings.getTitleTemplate()).isNull();
    }

    @Test
    void saveTitle_isAFeatureGate_beforeAnyValidation() {
        when(streamTitle.isAvailableFor(channel)).thenReturn(false);

        assertThatThrownBy(() -> service.saveTitle(channel, new TitleSettingsRequest(true, "x".repeat(200))))
                .isInstanceOf(ApiProblem.class);
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void saveTitle_refusesATemplateLongerThanATitle() {
        when(streamTitle.isAvailableFor(channel)).thenReturn(true);

        assertThatThrownBy(() -> service.saveTitle(channel, new TitleSettingsRequest(true, "x".repeat(141))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        verify(settingsRepository, never()).save(any());
    }
}
