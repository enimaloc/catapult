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

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void unban_executesDeleteRequest_whenTokenAndUserExist() {
        OAuthTokenRepository tokenRepo = mock(OAuthTokenRepository.class);
        TokenEncryptionService encSvc = mock(TokenEncryptionService.class);
        RestClient restClient = mock(RestClient.class);

        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec getResponseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        doReturn(getSpec).when(getSpec).uri(anyString());
        doReturn(getSpec).when(getSpec).header(anyString(), anyString());
        doReturn(getResponseSpec).when(getSpec).retrieve();
        doReturn(Map.of("data", List.of(Map.of("id", "target-id")))).when(getResponseSpec).body(Map.class);

        RestClient.RequestHeadersUriSpec deleteSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec deleteResponseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.delete()).thenReturn(deleteSpec);
        doReturn(deleteSpec).when(deleteSpec).uri(anyString());
        doReturn(deleteSpec).when(deleteSpec).header(anyString(), anyString());
        doReturn(deleteResponseSpec).when(deleteSpec).retrieve();

        IrcTwitchChatService service = new IrcTwitchChatService(
            tokenRepo, null, encSvc, mock(ApplicationEventPublisher.class), restClient, METER_REGISTRY);
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");

        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("bcast-id");
        OAuthToken token = new OAuthToken();
        token.setAccessToken("enc-token");
        when(tokenRepo.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));
        when(encSvc.decrypt("enc-token")).thenReturn("raw-token");

        service.unban(user, "targetLogin");

        verify(restClient).delete();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void ban_executesPostRequest_whenTokenAndUserExist() {
        OAuthTokenRepository tokenRepo = mock(OAuthTokenRepository.class);
        TokenEncryptionService encSvc = mock(TokenEncryptionService.class);
        RestClient restClient = mock(RestClient.class);

        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec getResponseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        doReturn(getSpec).when(getSpec).uri(anyString());
        doReturn(getSpec).when(getSpec).header(anyString(), anyString());
        doReturn(getResponseSpec).when(getSpec).retrieve();
        doReturn(Map.of("data", List.of(Map.of("id", "target-id")))).when(getResponseSpec).body(Map.class);

        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec postBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec postResponseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        doReturn(postBodySpec).when(postSpec).uri(anyString());
        doReturn(postBodySpec).when(postBodySpec).header(anyString(), anyString());
        doReturn(postBodySpec).when(postBodySpec).body(any());
        doReturn(postResponseSpec).when(postBodySpec).retrieve();

        IrcTwitchChatService service = new IrcTwitchChatService(
            tokenRepo, null, encSvc, mock(ApplicationEventPublisher.class), restClient, METER_REGISTRY);
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");

        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("bcast-id");
        OAuthToken token = new OAuthToken();
        token.setAccessToken("enc-token");
        when(tokenRepo.findByUserAndProvider(user, OAuthToken.Provider.TWITCH))
            .thenReturn(Optional.of(token));
        when(encSvc.decrypt("enc-token")).thenReturn("raw-token");

        service.ban(user, "targetLogin", "spam");

        verify(restClient).post();
    }

    private IrcTwitchChatService buildService(ApplicationEventPublisher publisher) {
        return new IrcTwitchChatService(null, null, null, publisher, null, METER_REGISTRY);
    }
}
