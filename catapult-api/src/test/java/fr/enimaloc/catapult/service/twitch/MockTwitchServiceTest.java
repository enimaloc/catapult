package fr.enimaloc.catapult.service.twitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockTwitchServiceTest {

    private final UserSettingsRepository settings = mock(UserSettingsRepository.class);
    private final MockTwitchService twitch = new MockTwitchService(mock(TwitchCategoryService.class), settings);
    private final UserAccount user = user();

    private static UserAccount user() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        return account;
    }

    @Test
    void remembersNothingBeforeAnyUpdate() {
        assertThat(twitch.lastCategoryName(user.getId())).isEmpty();
    }

    @Test
    void remembersTheBindingsCategory() {
        GameBinding binding = new GameBinding();
        binding.setTwitchGameName("Hades");

        twitch.updateChannelReporting(user, binding);

        assertThat(twitch.lastCategoryName(user.getId())).contains("Hades");
    }

    @Test
    void remembersASetCategory() {
        twitch.setCategory(user, "1", "Celeste");

        assertThat(twitch.lastCategoryName(user.getId())).contains("Celeste");
    }

    @Test
    void resetRemembersTheDefaultCategory() {
        twitch.setCategory(user, "1", "Celeste");
        UserSettings userSettings = new UserSettings();
        userSettings.setNoGameTwitchGameName("Just Chatting");
        when(settings.findById(user.getId())).thenReturn(Optional.of(userSettings));

        assertThat(twitch.resetToDefault(user)).isEqualTo(ChannelUpdateOutcome.APPLIED);

        assertThat(twitch.lastCategoryName(user.getId())).contains("Just Chatting");
    }

    @Test
    void resetWithoutSettingsKeepsTheCategory() {
        twitch.setCategory(user, "1", "Celeste");
        when(settings.findById(user.getId())).thenReturn(Optional.empty());

        twitch.resetToDefault(user);

        assertThat(twitch.lastCategoryName(user.getId())).contains("Celeste");
    }
}
