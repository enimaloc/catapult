package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.config.HttpTraceLoggingInterceptor;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.twitch.TwitchAccountValidationService;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The nightly Helix users?id=… validation batch: only the debugged member's share is recorded. */
class TwitchBatchDebugTest {

    private static final String URL = "https://api.twitch.tv/helix/users";

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final TwitchTokenService tokens = mock(TwitchTokenService.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer helix;
    private TwitchAccountValidationService service;

    private final UserAccount a = member("101", "alice");
    private final UserAccount b = member("102", "bob");
    private final UserAccount c = member("103", "carol");

    private static UserAccount member(String twitchId, String login) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId(twitchId);
        user.setTwitchUsername(login);
        user.setStatus(UserAccount.Status.ACTIVE);
        return user;
    }

    @BeforeEach
    void setUp() {
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new HttpTraceLoggingInterceptor(registry, recorder));
        helix = MockRestServiceServer.bindTo(builder).build();
        service = new TwitchAccountValidationService(accounts, tokens, builder.build());
        ReflectionTestUtils.setField(service, "twitchClientId", "client-id");
        service.setDebugRecording(new DebugRecording(registry, recorder));
        when(accounts.findByStatusAndTwitchIdNotNull(UserAccount.Status.ACTIVE)).thenReturn(List.of(a, b, c));
        when(tokens.getAppAccessToken()).thenReturn("app-token");
        helix.expect(requestTo(URL + "?id=101&id=102&id=103")).andRespond(withSuccess("""
                {"data":[{"id":"101","login":"alice"},{"id":"102","login":"bob"},{"id":"103","login":"carol"}]}
                """, MediaType.APPLICATION_JSON));
    }

    @Test
    void onlyTheDebuggedMembersShareIsRecorded() {
        when(registry.modeOf(b.getId())).thenReturn(Optional.of(new DebugMode(b.getId(), false)));
        when(registry.hasAny()).thenReturn(true);

        service.validateAccounts();

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(b.getId()), eq(Direction.OUT), raw.capture(), eq("twitch.get_users"), eq(true));
        verify(recorder, never()).record(eq(a.getId()), any(), any(), any(), anyBoolean());
        verify(recorder, never()).record(eq(c.getId()), any(), any(), any(), anyBoolean());
        assertThat(raw.getValue().uri().toString()).isEqualTo(URL + "?id=102");
        JsonNode data = json.readTree(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8)).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("login").asString()).isEqualTo("bob");
        // The validation itself still sees the whole batch: nobody is marked inactive.
        assertThat(List.of(a, b, c)).allMatch(u -> u.getStatus() == UserAccount.Status.ACTIVE);
    }

    @Test
    void noDebuggedMemberInBatch_nothingRecorded() {
        service.validateAccounts();

        helix.verify();
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean());
    }
}
