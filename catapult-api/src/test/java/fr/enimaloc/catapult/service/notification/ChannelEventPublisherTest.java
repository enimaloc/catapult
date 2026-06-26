package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.service.binding.BindingDto;
import fr.enimaloc.catapult.service.connections.ProviderConnectionsDto;
import fr.enimaloc.catapult.service.connections.SteamProfileDto;
import fr.enimaloc.catapult.service.settings.UserSettingsDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChannelEventPublisherTest {

    @Test
    void bindingUpserted_publishesFullDto() {
        RedisEventPublisher redis = mock(RedisEventPublisher.class);
        ChannelEventPublisher publisher = new ChannelEventPublisher(redis);
        UUID ownerId = UUID.randomUUID();
        BindingDto dto = new BindingDto(
                UUID.randomUUID().toString(),
                "STEAM",
                "Skyrim",
                "Skyrim",
                Set.of("ccl1"),
                "AUTO",
                true,
                false
        );

        publisher.bindingUpserted(ownerId, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publishChannel(eq(ownerId), eq("binding.upserted"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKey("binding");
        assertThat(payload.get("binding")).isEqualTo(dto);
    }

    @Test
    void settingsUpdated_publishesFullDto() {
        RedisEventPublisher redis = mock(RedisEventPublisher.class);
        ChannelEventPublisher publisher = new ChannelEventPublisher(redis);
        UUID ownerId = UUID.randomUUID();
        UserSettingsDto dto = new UserSettingsDto(
                new UserSettingsDto.Ccl(true, Set.of("ccl1")),
                new UserSettingsDto.Tw(false, Set.of()),
                new UserSettingsDto.NoGame("123", "Just Chatting", Set.of("ccl2"), true, true, false),
                new UserSettingsDto.IncompleteFallback("456", "Science & Technology", Set.of())
        );

        publisher.settingsUpdated(ownerId, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publishChannel(eq(ownerId), eq("settings.updated"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKey("settings");
        assertThat(payload.get("settings")).isEqualTo(dto);
    }

    @Test
    void steamProfileChanged_publishesFullDto() {
        RedisEventPublisher redis = mock(RedisEventPublisher.class);
        ChannelEventPublisher publisher = new ChannelEventPublisher(redis);
        UUID ownerId = UUID.randomUUID();
        SteamProfileDto dto = new SteamProfileDto(true, false, true, false, 15L, true, false);

        publisher.steamProfileChanged(ownerId, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publishChannel(eq(ownerId), eq("steam.profile.changed"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKey("profile");
        assertThat(payload.get("profile")).isEqualTo(dto);
    }

    @Test
    void connectionChanged_publishesFullDto() {
        RedisEventPublisher redis = mock(RedisEventPublisher.class);
        ChannelEventPublisher publisher = new ChannelEventPublisher(redis);
        UUID ownerId = UUID.randomUUID();
        ProviderConnectionsDto dto = new ProviderConnectionsDto("TWITCH", false, null);

        publisher.connectionChanged(ownerId, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(redis).publishChannel(eq(ownerId), eq("connection.changed"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsKey("provider");
        assertThat(payload.get("provider")).isEqualTo(dto);
    }
}
