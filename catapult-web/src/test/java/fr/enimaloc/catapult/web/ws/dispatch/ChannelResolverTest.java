package fr.enimaloc.catapult.web.ws.dispatch;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.web.ws.WsSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.WebSocketSession;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelResolverTest {

    private ApiClient apiClient;
    private ChannelResolver resolver;

    @BeforeEach
    void setUp() {
        apiClient = Mockito.mock(ApiClient.class);
        resolver = new ChannelResolver(apiClient);
    }

    private WsSession unauthSession() {
        var spring = Mockito.mock(WebSocketSession.class);
        Mockito.when(spring.getId()).thenReturn("s1");
        return new WsSession(spring);
    }

    private WsSession authSession(UUID userId, String... roles) {
        var s = unauthSession();
        s.authenticate(userId, Set.of(roles));
        return s;
    }

    @Test
    void events_global_is_public() {
        assertThat(resolver.resolvePublicToInternal("events.global", unauthSession()))
                .contains("events.global");
    }

    @Test
    void notifications_user_requires_auth() {
        assertThat(resolver.resolvePublicToInternal("notifications.user", unauthSession()))
                .isEmpty();
    }

    @Test
    void notifications_user_expands_to_uuid_when_authenticated() {
        UUID uid = UUID.randomUUID();
        Optional<String> res = resolver.resolvePublicToInternal("notifications.user", authSession(uid));
        assertThat(res).contains("notifications.user." + uid);
    }

    @Test
    void events_admin_requires_admin_role() {
        UUID uid = UUID.randomUUID();
        assertThat(resolver.resolvePublicToInternal("events.admin", authSession(uid, "USER"))).isEmpty();
        assertThat(resolver.resolvePublicToInternal("events.admin", authSession(uid, "ADMIN"))).contains("events.admin");
    }

    @Test
    void unknown_channel_returns_empty() {
        assertThat(resolver.resolvePublicToInternal("totally.made.up", unauthSession())).isEmpty();
    }

    @Test
    void channel_viewed_requires_auth() {
        UUID owner = UUID.randomUUID();
        Optional<String> res = resolver.resolvePublicToInternal(
                "channel.viewed." + owner, unauthSession());
        assertThat(res).isEmpty();
        verify(apiClient, never()).get(any(), eq(ChannelResolver.AccessResponse.class), any(Object[].class));
    }

    @Test
    void channel_viewed_malformed_uuid_returns_empty_without_api_call() {
        Optional<String> res = resolver.resolvePublicToInternal(
                "channel.viewed.not-a-uuid", authSession(UUID.randomUUID()));
        assertThat(res).isEmpty();
        verify(apiClient, never()).get(any(), eq(ChannelResolver.AccessResponse.class), any(Object[].class));
    }

    @Test
    void channel_viewed_api_denies_returns_empty() {
        UUID owner = UUID.randomUUID();
        when(apiClient.get(
                eq("/api/users/{ownerId}/channel-access"),
                eq(ChannelResolver.AccessResponse.class),
                any(Object[].class)))
                .thenReturn(new ChannelResolver.AccessResponse(false));
        Optional<String> res = resolver.resolvePublicToInternal(
                "channel.viewed." + owner, authSession(UUID.randomUUID()));
        assertThat(res).isEmpty();
    }

    @Test
    void channel_viewed_api_unreachable_returns_empty() {
        UUID owner = UUID.randomUUID();
        when(apiClient.get(
                eq("/api/users/{ownerId}/channel-access"),
                eq(ChannelResolver.AccessResponse.class),
                any(Object[].class)))
                .thenReturn(null);
        Optional<String> res = resolver.resolvePublicToInternal(
                "channel.viewed." + owner, authSession(UUID.randomUUID()));
        assertThat(res).isEmpty();
    }

    @Test
    void channel_viewed_api_allows_returns_internal_name() {
        UUID owner = UUID.randomUUID();
        when(apiClient.get(
                eq("/api/users/{ownerId}/channel-access"),
                eq(ChannelResolver.AccessResponse.class),
                any(Object[].class)))
                .thenReturn(new ChannelResolver.AccessResponse(true));
        Optional<String> res = resolver.resolvePublicToInternal(
                "channel.viewed." + owner, authSession(UUID.randomUUID()));
        assertThat(res).contains("channel.viewed." + owner);
    }

    @Test
    void resolveRedisToInternal_global() {
        assertThat(resolver.resolveRedisToInternal("catapult:events:global")).isEqualTo("events.global");
    }

    @Test
    void resolveRedisToInternal_admin() {
        assertThat(resolver.resolveRedisToInternal("catapult:events:admin")).isEqualTo("events.admin");
    }

    @Test
    void resolveRedisToInternal_user_keeps_uuid() {
        UUID uid = UUID.randomUUID();
        assertThat(resolver.resolveRedisToInternal("catapult:events:user:" + uid))
                .isEqualTo("notifications.user." + uid);
    }

    @Test
    void resolveRedisToInternal_channel_maps_to_viewed_prefix() {
        UUID owner = UUID.randomUUID();
        assertThat(resolver.resolveRedisToInternal("catapult:events:channel:" + owner))
                .isEqualTo("channel.viewed." + owner);
    }

    @Test
    void resolveRedisToInternal_unknown_returns_null() {
        assertThat(resolver.resolveRedisToInternal("not.a.catapult.channel")).isNull();
    }
}
