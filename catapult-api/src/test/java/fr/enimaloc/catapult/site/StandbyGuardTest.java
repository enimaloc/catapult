package fr.enimaloc.catapult.site;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.metrics.CatapultApiMetrics;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import fr.enimaloc.catapult.service.minecraft.MsaAuthClient;
import fr.enimaloc.catapult.service.notification.RedisEventPublisher;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.IrcTwitchChatService;
import fr.enimaloc.catapult.service.twitch.SystemTwitchAccountService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import fr.enimaloc.catapult.service.xbox.XboxUserTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Every outbound write choke point refuses, before touching the outside world, when this site is on standby. */
class StandbyGuardTest {

    private final SiteRole siteRole = mock(SiteRole.class);
    private final RestClient restClient = mock(RestClient.class);
    private final UserAccount user = new UserAccount();

    @BeforeEach
    void standby() {
        user.setId(UUID.randomUUID());
        doThrow(new StandbyException("x")).when(siteRole).requireActive(any());
    }

    @Test
    void ircChatBanIsRefused() {
        var helix = mock(fr.enimaloc.catapult.service.twitch.TwitchHelixChannelClient.class);
        var service = new IrcTwitchChatService(null, null, null, null, helix, null, null, siteRole);
        assertThrows(StandbyException.class, () -> service.ban(user, "target", "r"));
        verifyNoInteractions(helix);
    }

    @Test
    void ircChatSendIsRefused() {
        var service = new IrcTwitchChatService(null, null, null, null, null, null, null, siteRole);
        assertThrows(StandbyException.class, () -> service.sendMessage(user, "hi"));
    }

    @Test
    void eventSubChatBanIsRefused() {
        var helix = mock(fr.enimaloc.catapult.service.twitch.TwitchHelixChannelClient.class);
        var service = new EventSubTwitchChatService(null, null, null, null, helix, restClient, null, null, null,
                null, null, null, null, siteRole);
        assertThrows(StandbyException.class, () -> service.ban(user, "target", "r"));
        verifyNoInteractions(helix, restClient);
    }

    @Test
    void eventSubChatSendIsRefused() {
        var service = new EventSubTwitchChatService(null, null, null, null, null, restClient, null, null, null,
                null, null, null, null, siteRole);
        assertThrows(StandbyException.class, () -> service.sendMessage(user, "hi"));
        verifyNoInteractions(restClient);
    }

    @Test
    void twitchChannelUpdateIsRefused() {
        var service = new TwitchServiceImpl(null, null, null, restClient, null, null, null, null, null, siteRole);
        assertThrows(StandbyException.class, () -> service.updateChannelReporting(user, new GameBinding()));
        assertThrows(StandbyException.class, () -> service.setCategory(user, "1", "g"));
        assertThrows(StandbyException.class, () -> service.resetToDefault(user));
        assertThrows(StandbyException.class, () -> service.setActiveExtensions(user, null));
        verifyNoInteractions(restClient);
    }

    @Test
    void minecraftWritesAreRefusedWhicheverOverloadIsUsed() {
        var service = new MinecraftService(restClient, siteRole);
        assertThrows(StandbyException.class, () -> service.addFriend("t", "n", null));
        assertThrows(StandbyException.class, () -> service.removeFriend("t", "n", null));
        assertThrows(StandbyException.class, () -> service.updatePresence("t", MinecraftService.PresenceStatus.OFFLINE));
        verifyNoInteractions(restClient);
    }

    @Test
    void twitchatPushIsRefused() {
        var redis = mock(StringRedisTemplate.class);
        var publisher = new RedisEventPublisher(redis, mock(ObjectMapper.class), mock(CatapultApiMetrics.class),
                siteRole);
        assertThrows(StandbyException.class, () -> publisher.publishTwitchat(UUID.randomUUID(), "e", "d"));
        verifyNoInteractions(redis);
    }

    @Test
    void twitchTokenRefreshIsRefused() {
        var service = new TwitchTokenService(null, null, restClient, siteRole);
        assertThrows(StandbyException.class, () -> service.refreshAccessToken(new OAuthToken(), user));
        verifyNoInteractions(restClient);
    }

    @Test
    void botTokenRefreshIsRefused() {
        var service = new SystemTwitchAccountService(null, null, null, restClient, siteRole);
        assertThrows(StandbyException.class, service::refresh);
        verifyNoInteractions(restClient);
    }

    @Test
    void msaTokenRefreshIsRefused() {
        var client = new MsaAuthClient(restClient, "id", siteRole);
        assertThrows(StandbyException.class, () -> client.refresh("r"));
        verifyNoInteractions(restClient);
    }

    @Test
    void xboxChainRefreshStaysQuietAndTouchesNothing() {
        var tokens = mock(fr.enimaloc.catapult.repository.account.OAuthTokenRepository.class);
        var xbox = mock(fr.enimaloc.catapult.service.xbox.XboxService.class);
        OAuthToken stored = new OAuthToken();
        stored.setRefreshToken("enc");
        when(tokens.findByUserAndProvider(user, OAuthToken.Provider.XBOX)).thenReturn(Optional.of(stored));
        var service = new XboxUserTokenService(null, tokens, null, xbox, null,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), siteRole);

        assertTrue(service.getToken(user).isEmpty());
        verifyNoInteractions(xbox);
    }
}
