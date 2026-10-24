package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.channel.BindingDto;
import fr.enimaloc.catapult.common.event.ChannelEvents;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelEventPublisherTest {

    private final RedisEventPublisher redis = mock(RedisEventPublisher.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final GameBindingRepository bindings = mock(GameBindingRepository.class);
    private final ChannelEventPublisher publisher = new ChannelEventPublisher(redis, accounts, bindings);
    private final UUID ownerId = UUID.randomUUID();
    private UserAccount owner;

    @BeforeEach
    void setUp() {
        owner = new UserAccount();
        owner.setId(ownerId);
        owner.setTwitchUsername("StreamerName");
        when(accounts.findById(ownerId)).thenReturn(Optional.of(owner));
    }

    private Object published(String name) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(redis).publishChannel(eq(ownerId), eq(name), captor.capture());
        return captor.getValue();
    }

    @Test
    void everyPayload_namesTheChannelByIdAndLowerCaseLogin() {
        publisher.botToggled(ownerId, true);

        assertThat(published(ChannelEvents.BOT_TOGGLED))
                .isEqualTo(new ChannelEvents.BotToggled(ownerId.toString(), "streamername", true));
    }

    @Test
    void unknownOwner_publishesNothing() {
        publisher.streamStateChanged(UUID.randomUUID(), true);

        verify(redis, never()).publishChannel(any(), anyString(), any());
    }

    @Test
    void bindingUpserted_publishesTheBindingsWholeState() {
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        binding.setSourceType(GameBinding.SourceType.STEAM);
        binding.setSourceName("Skyrim");
        binding.setTwitchGameId("42");
        binding.setTwitchGameName("Skyrim");
        binding.setStatus(GameBinding.Status.AUTO);
        binding.getCcls().add("ccl1");
        binding.setTwOverride(true);
        binding.getTws().add("tw1");

        publisher.bindingUpserted(ownerId, binding);

        assertThat(published(ChannelEvents.BINDING_UPSERTED)).isEqualTo(new ChannelEvents.BindingUpserted(
                ownerId.toString(), "streamername", new BindingDto(binding.getId().toString(), "AUTO", "STEAM",
                "Skyrim", "42", "Skyrim", false, true, Set.of("ccl1"), true, true, Set.of("tw1"))));
    }

    @Test
    void gameDetected_carriesTheBindingOfThatGame() {
        GameBinding binding = new GameBinding();
        binding.setId(UUID.randomUUID());
        DetectedGame game = new DetectedGame("570", GameBinding.SourceType.STEAM, "Dota 2");
        when(bindings.findByUserAndSourceIdAndSourceType(owner, "570", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.of(binding));

        publisher.gameDetected(ownerId, game);

        assertThat(published(ChannelEvents.GAME_DETECTED)).isEqualTo(new ChannelEvents.GameDetected(
                ownerId.toString(), "streamername", binding.getId().toString(), "Dota 2", "STEAM"));
    }

    @Test
    void gameDetected_withoutAGame_clearsIt() {
        publisher.gameDetected(ownerId, null);

        assertThat(published(ChannelEvents.GAME_CLEARED))
                .isEqualTo(new ChannelEvents.GameCleared(ownerId.toString(), "streamername"));
    }

    @Test
    void settingsUpdated_publishesEachPanel() {
        UserSettings settings = new UserSettings();
        settings.setCclFeatureEnabled(true);
        settings.getBlockedCcls().clear();
        settings.getBlockedCcls().add("ccl1");
        settings.setNoGameTwitchGameName("Just Chatting");
        settings.setRaidRestoreGameMinutes(20);

        publisher.settingsUpdated(ownerId, settings);

        ChannelEvents.SettingsUpdated payload = (ChannelEvents.SettingsUpdated) published(ChannelEvents.SETTINGS_UPDATED);
        assertThat(payload.settings().ccl()).isEqualTo(new ChannelEvents.Settings.Ccl(true, Set.of("ccl1")));
        assertThat(payload.settings().noGame().twitchGameName()).isEqualTo("Just Chatting");
        assertThat(payload.settings().raid()).isEqualTo(new ChannelEvents.Settings.Raid(20));
    }

    @Test
    void steamProfileChanged_publishesTheProfile() {
        publisher.steamProfileChanged(ownerId, new SteamProfileDto(true, false, true, false, 15L, true, false));

        assertThat(published(ChannelEvents.STEAM_PROFILE_CHANGED)).isEqualTo(new ChannelEvents.SteamProfileChanged(
                ownerId.toString(), "streamername", new ChannelEvents.SteamProfile(true, false, true, false, 15L, true, false)));
    }

    @Test
    void connectionChanged_publishesTheProvidersState() {
        publisher.connectionChanged(ownerId, new ProviderConnectionsDto("TWITCH", false, null));

        assertThat(published(ChannelEvents.CONNECTION_CHANGED)).isEqualTo(
                new ChannelEvents.ConnectionChanged(ownerId.toString(), "streamername", "TWITCH", false, null));
    }

    @Test
    void minecraftLinkChanged_publishesTheLinkState() {
        publisher.minecraftLinkChanged(ownerId, "PENDING", "jeb_");

        assertThat(published(ChannelEvents.MINECRAFT_LINK_CHANGED)).isEqualTo(
                new ChannelEvents.MinecraftLinkChanged(ownerId.toString(), "streamername", "PENDING", "jeb_"));
    }

    @Test
    void customBindingChanged_publishesTheActiveOne() {
        UUID active = UUID.randomUUID();
        publisher.customBindingChanged(ownerId, active);
        assertThat(published(ChannelEvents.CUSTOM_BINDING_CHANGED)).isEqualTo(
                new ChannelEvents.CustomBindingChanged(ownerId.toString(), "streamername", active.toString()));
    }

    @Test
    void customBindingChanged_deactivated_publishesNull() {
        publisher.customBindingChanged(ownerId, null);
        assertThat(published(ChannelEvents.CUSTOM_BINDING_CHANGED)).isEqualTo(
                new ChannelEvents.CustomBindingChanged(ownerId.toString(), "streamername", null));
    }

    @Test
    void customBindingsUpdated_namesTheChannel() {
        publisher.customBindingsUpdated(ownerId);
        assertThat(published(ChannelEvents.CUSTOM_BINDINGS_UPDATED)).isEqualTo(
                new ChannelEvents.CustomBindingsUpdated(ownerId.toString(), "streamername"));
    }
}
