package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.OAuthTokenRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IrcTwitchChatServiceTest {

    private static final MeterRegistry METER_REGISTRY = new SimpleMeterRegistry();

    @Test
    void extractRoleBroadcaster() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/12;badges=broadcaster/1,subscriber/0;color=#FF0000");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void extractRoleModerator() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=;badges=moderator/1;color=#00FF00");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.MODERATOR);
    }

    @Test
    void extractRoleSubsWhenSubscriberBadgePresent() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/3;badges=subscriber/3;color=");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.SUBS);
    }

    @Test
    void extractRoleVipWhenVipBadgePresent() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=;badges=vip/1;color=");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.VIP);
    }

    @Test
    void extractRoleModeratorOutranksSubscriberBadge() {
        var role = IrcTwitchChatService.extractRole(
            "badge-info=subscriber/3;badges=moderator/1,subscriber/3;color=");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.MODERATOR);
    }

    @Test
    void extractRoleEveryoneWhenEmptyTags() {
        var role = IrcTwitchChatService.extractRole("");
        assertThat(role).isEqualTo(ChatCommandEvent.SenderRole.VIEWERS);
    }

    @Test
    void extractSenderIdFromTags() {
        var id = IrcTwitchChatService.extractSenderId(
            "badges=broadcaster/1;color=#FF0000;user-id=123456;user-type=");
        assertThat(id).isEqualTo("123456");
    }

    @Test
    void extractSenderIdNullWhenAbsentOrEmpty() {
        assertThat(IrcTwitchChatService.extractSenderId("badges=;color=")).isNull();
        assertThat(IrcTwitchChatService.extractSenderId("user-id=;color=")).isNull();
    }

    @Test
    void handleLinePongOnPing() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IrcTwitchChatService service = buildService(publisher);
        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        service.handleLine(user, writer, "PING :tmi.twitch.tv");

        assertThat(sw.toString().trim()).isEqualTo("PONG :tmi.twitch.tv");
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void handleLinePublishesChatCommandEvent() {
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        IrcTwitchChatService service = buildService(publisher);

        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        String line = "@badges=broadcaster/1;color=#FF0000 :streamer!streamer@streamer.tmi.twitch.tv PRIVMSG #streamer :!game";
        service.handleLine(user, writer, line);

        assertThat(published).hasSize(1);
        ChatCommandEvent event = (ChatCommandEvent) published.get(0);
        assertThat(event.getCommand()).isEqualTo("!game");
        assertThat(event.getArgs()).isEmpty();
        assertThat(event.getSenderRole()).isEqualTo(ChatCommandEvent.SenderRole.BROADCASTER);
    }

    @Test
    void handleLineIgnoresNonCommandMessages() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        IrcTwitchChatService service = buildService(publisher);

        UserAccount user = new UserAccount();
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw, true);

        service.handleLine(user, writer,
            "@badges= :viewer!viewer@viewer.tmi.twitch.tv PRIVMSG #streamer :bonjour");

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void helixCalls_runWithTheStreamersDecryptedToken() {
        OAuthTokenRepository tokenRepo = mock(OAuthTokenRepository.class);
        TokenEncryptionService encSvc = mock(TokenEncryptionService.class);
        TwitchHelixChannelClient helix = mock(TwitchHelixChannelClient.class);
        IrcTwitchChatService service = new IrcTwitchChatService(
            tokenRepo, null, encSvc, mock(ApplicationEventPublisher.class), helix, METER_REGISTRY, newRateLimiter());
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        OAuthToken token = new OAuthToken();
        token.setAccessToken("enc-token");
        when(tokenRepo.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(token));
        when(encSvc.decrypt("enc-token")).thenReturn("raw-token");
        Instant followedAt = Instant.parse("2024-01-01T00:00:00Z");
        when(helix.followedAt(user, "raw-token", "fan")).thenReturn(Optional.of(followedAt));
        when(helix.followedAtById(user, "raw-token", "42")).thenReturn(Optional.of(followedAt));
        TwitchStreamInfo live = new TwitchStreamInfo("t", "g", 1, followedAt);
        when(helix.streamInfo(user, "raw-token")).thenReturn(Optional.of(live));
        TwitchUserProfile profile = new TwitchUserProfile("Fan", followedAt);
        when(helix.userProfile("raw-token", "fan")).thenReturn(Optional.of(profile));

        service.ban(user, "troll", "spam");
        service.timeout(user, "troll", 60, null);
        service.timeout(user, "troll", 0, null);
        service.unban(user, "troll");
        service.shoutout(user, "friend");

        verify(helix).moderate(user, "raw-token", "troll", 0, "spam");
        verify(helix).moderate(user, "raw-token", "troll", 60, null);
        verify(helix).unban(user, "raw-token", "troll");
        verify(helix).shoutout(user, "raw-token", "friend");
        assertThat(service.getFollowedAt(user, "fan")).contains(followedAt);
        assertThat(service.getFollowedAtById(user, "42")).contains(followedAt);
        assertThat(service.getStreamInfo(user)).contains(live);
        assertThat(service.getUserProfile(user, "fan")).contains(profile);
    }

    @Test
    void helixCalls_needATwitchToken() {
        OAuthTokenRepository tokenRepo = mock(OAuthTokenRepository.class);
        TwitchHelixChannelClient helix = mock(TwitchHelixChannelClient.class);
        IrcTwitchChatService service = new IrcTwitchChatService(
            tokenRepo, null, null, mock(ApplicationEventPublisher.class), helix, METER_REGISTRY, newRateLimiter());
        UserAccount user = new UserAccount();
        when(tokenRepo.findByUserAndProvider(user, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());

        service.ban(user, "troll", "spam");
        service.unban(user, "troll");

        assertThat(service.getStreamInfo(user)).isEmpty();
        assertThat(service.getFollowedAt(user, "fan")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(helix);
    }

    @Test
    void sendMessageWritesPrivmsgWhenRateLimiterHasCapacity() {
        IrcTwitchChatService service = new IrcTwitchChatService(
            null, null, null, mock(ApplicationEventPublisher.class), null, METER_REGISTRY, newRateLimiter());
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("bcast-id");
        user.setTwitchUsername("streamer");
        StringWriter sw = new StringWriter();
        writersOf(service).put(user.getId(), new PrintWriter(sw, true));

        service.sendMessage(user, "hello chat");

        assertThat(sw.toString().trim()).isEqualTo("PRIVMSG #streamer :hello chat");
    }

    @Test
    void sendMessageSkippedWhenRateLimiterHasNoCapacity() {
        // permitsPerWindow=0, maxWaitMs=0 -> acquire() always fails immediately
        TwitchChatRateLimiter exhaustedLimiter = new TwitchChatRateLimiter(0, 0, 30000, METER_REGISTRY);
        IrcTwitchChatService service = new IrcTwitchChatService(
            null, null, null, mock(ApplicationEventPublisher.class), null, METER_REGISTRY, exhaustedLimiter);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("bcast-id");
        user.setTwitchUsername("streamer");
        StringWriter sw = new StringWriter();
        writersOf(service).put(user.getId(), new PrintWriter(sw, true));

        service.sendMessage(user, "hello chat");

        assertThat(sw.toString()).isEmpty();
    }

    private static TwitchChatRateLimiter newRateLimiter() {
        return new TwitchChatRateLimiter(18, 3000, 30000, METER_REGISTRY);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, PrintWriter> writersOf(IrcTwitchChatService service) {
        return (Map<UUID, PrintWriter>) ReflectionTestUtils.getField(service, "writers");
    }

    private IrcTwitchChatService buildService(ApplicationEventPublisher publisher) {
        return new IrcTwitchChatService(null, null, null, publisher, null, METER_REGISTRY, newRateLimiter());
    }
}
