package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelAccessServiceTest {

    @Mock TwitchService twitchService;
    @Mock UserAccountRepository userAccountRepository;
    @InjectMocks ChannelAccessService channelAccessService;

    private UserAccount owner;
    private UserAccount moderator;
    private UserAccount stranger;

    @BeforeEach
    void setup() {
        owner = account("owner-twitch-id", "owner");
        moderator = account("mod-twitch-id", "moderator");
        stranger = account("stranger-twitch-id", "stranger");
    }

    @Test
    void owner_always_has_access_without_twitch_call() {
        assertThat(channelAccessService.canAccess(owner, owner)).isTrue();
        verifyNoInteractions(twitchService);
    }

    @Test
    void moderator_with_confirmed_twitch_status_has_access() {
        when(twitchService.getModeratedChannelIds(moderator))
            .thenReturn(List.of("owner-twitch-id"));
        assertThat(channelAccessService.canAccess(moderator, owner)).isTrue();
    }

    @Test
    void user_not_in_mod_list_is_denied() {
        when(twitchService.getModeratedChannelIds(stranger))
            .thenReturn(List.of("some-other-channel-id"));
        assertThat(channelAccessService.canAccess(stranger, owner)).isFalse();
    }

    @Test
    void cache_prevents_second_twitch_api_call_within_ttl() {
        when(twitchService.getModeratedChannelIds(moderator))
            .thenReturn(List.of("owner-twitch-id"));
        channelAccessService.canAccess(moderator, owner);
        channelAccessService.canAccess(moderator, owner);
        verify(twitchService, times(1)).getModeratedChannelIds(moderator);
    }

    @Test
    void expired_cache_triggers_fresh_twitch_api_call() throws Exception {
        java.lang.reflect.Field cacheField = ChannelAccessService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<java.util.UUID, Object> cache =
            (java.util.Map<java.util.UUID, Object>) cacheField.get(channelAccessService);

        Class<?> cachedModStatusClass = Class.forName(
            "fr.enimaloc.catapult.service.ChannelAccessService$CachedModStatus");
        java.lang.reflect.Constructor<?> ctor =
            cachedModStatusClass.getDeclaredConstructor(java.util.Set.class, java.time.Instant.class);
        ctor.setAccessible(true);
        Object expiredEntry = ctor.newInstance(
            java.util.Set.of("owner-twitch-id"),
            java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
        cache.put(moderator.getId(), expiredEntry);

        when(twitchService.getModeratedChannelIds(moderator))
            .thenReturn(List.of("owner-twitch-id"));

        assertThat(channelAccessService.canAccess(moderator, owner)).isTrue();

        verify(twitchService, times(1)).getModeratedChannelIds(moderator);
    }

    @Test
    void twitch_api_error_denies_access_fail_closed() {
        when(twitchService.getModeratedChannelIds(moderator))
            .thenThrow(new RuntimeException("Twitch API down"));
        assertThat(channelAccessService.canAccess(moderator, owner)).isFalse();
    }

    @Test
    void getAccessibleChannels_includes_own_channel_and_moderated() {
        UserAccount modded = account("other-channel-id", "other");
        when(twitchService.getModeratedChannelIds(moderator))
            .thenReturn(List.of("other-channel-id"));
        when(userAccountRepository.findByTwitchIdIn(java.util.Set.of("other-channel-id")))
            .thenReturn(List.of(modded));

        List<UserAccount> result = channelAccessService.getAccessibleChannels(moderator);

        assertThat(result).containsExactly(moderator, modded);
        assertThat(result).doesNotContain(owner);
    }

    private UserAccount account(String twitchId, String username) {
        UserAccount ua = new UserAccount();
        ua.setId(UUID.randomUUID());
        ua.setTwitchId(twitchId);
        ua.setTwitchUsername(username);
        ua.setStatus(UserAccount.Status.ACTIVE);
        return ua;
    }
}
