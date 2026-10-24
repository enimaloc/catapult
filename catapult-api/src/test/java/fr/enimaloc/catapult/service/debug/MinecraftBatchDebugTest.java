package fr.enimaloc.catapult.service.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.config.HttpTraceLoggingInterceptor;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.domain.minecraft.MinecraftServiceAccount;
import fr.enimaloc.catapult.getter.MinecraftPresenceGetter;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry.DebugMode;
import fr.enimaloc.catapult.service.minecraft.MinecraftAccountLimitMarker;
import fr.enimaloc.catapult.service.minecraft.MinecraftFriendService;
import fr.enimaloc.catapult.service.minecraft.MinecraftGateService;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import fr.enimaloc.catapult.service.minecraft.MinecraftTokenService;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

/**
 * The Minecraft calls made once per service account (presence, friend list) cover every member befriended by that
 * account: only the debugged member's share is recorded, the member being known by its profile id.
 */
class MinecraftBatchDebugTest {

    private static final String API = "https://api.minecraftservices.com";
    // Dashed in catapult, undashed and upper case in this response: matched once normalized.
    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "2222aaaa-2222-2222-2222-222222222222";
    private static final String BOB_UNDASHED_UPPER = "2222AAAA222222222222222222222222";

    private final DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
    private final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    private final MinecraftTokenService tokens = mock(MinecraftTokenService.class);
    private final MinecraftServiceAccountRepository serviceAccounts = mock(MinecraftServiceAccountRepository.class);
    private final MinecraftFriendLinkRepository links = mock(MinecraftFriendLinkRepository.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private MockRestServiceServer mojang;
    private MinecraftService minecraft;

    private final MinecraftServiceAccount account = new MinecraftServiceAccount();
    private final MinecraftFriendLink aliceLink = link(ALICE, "alice");
    private final MinecraftFriendLink bobLink = link(BOB, "bob");

    private MinecraftFriendLink link(String profileId, String name) {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        MinecraftFriendLink l = new MinecraftFriendLink();
        l.setUser(user);
        l.setServiceAccount(account);
        l.setMinecraftProfileId(profileId);
        l.setMinecraftName(name);
        l.setStatus(MinecraftFriendLink.Status.ACCEPTED);
        return l;
    }

    @BeforeEach
    void setUp() {
        account.setId(UUID.randomUUID());
        account.setLabel("svc-1");
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new HttpTraceLoggingInterceptor(registry, recorder));
        mojang = MockRestServiceServer.bindTo(builder).build();
        minecraft = new MinecraftService(builder.build());
        when(serviceAccounts.findByEnabledTrueOrderByFillOrderAsc()).thenReturn(List.of(account));
        when(links.existsByServiceAccountAndStatus(account, MinecraftFriendLink.Status.ACCEPTED)).thenReturn(true);
        when(links.findByServiceAccount(account)).thenReturn(List.of(aliceLink, bobLink));
        when(tokens.getToken(account)).thenReturn(Optional.of("svc-token"));
    }

    private void debugged(MinecraftFriendLink link) {
        UUID id = link.getUser().getId();
        when(registry.modeOf(id)).thenReturn(Optional.of(new DebugMode(id, false)));
        when(registry.hasAny()).thenReturn(true);
    }

    private MinecraftPresenceGetter presenceGetter() {
        MinecraftPresenceGetter getter = new MinecraftPresenceGetter(minecraft, tokens, serviceAccounts, links,
                mock(MinecraftGateService.class));
        getter.setDebugRecording(new DebugRecording(registry, recorder));
        return getter;
    }

    private void respondWithBothPresences() {
        String now = Instant.now().toString();
        mojang.expect(requestTo(API + "/presence")).andRespond(withSuccess("""
                {"presence":[
                  {"profileId":"11111111111111111111111111111111","status":"ONLINE","lastUpdated":"%s"},
                  {"profileId":"%s","status":"PLAYING_SERVER","lastUpdated":"%s"}]}
                """.formatted(now, BOB_UNDASHED_UPPER, now), MediaType.APPLICATION_JSON));
    }

    @Test
    void minecraftPresence_keepsOnlyTheMembersPresence() throws Exception {
        debugged(bobLink);
        respondWithBothPresences();

        presenceGetter().prefetchBatch().get(5, TimeUnit.SECONDS);

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(bobLink.getUser().getId()), eq(Direction.OUT), raw.capture(),
                eq("minecraft.presence"), eq(true));
        verify(recorder, never()).record(eq(aliceLink.getUser().getId()), any(), any(), any(), anyBoolean());
        JsonNode presence = json.readTree(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8))
                .path("presence");
        assertThat(presence).hasSize(1);
        assertThat(presence.get(0).path("profileId").asString()).isEqualTo(BOB_UNDASHED_UPPER);
        assertThat(raw.getValue().uri().toString()).isEqualTo(API + "/presence");
    }

    @Test
    void minecraftPresence_nobodyInDebug_doesNotEvenLookUpTheMembers() throws Exception {
        respondWithBothPresences();

        presenceGetter().prefetchBatch().get(5, TimeUnit.SECONDS);

        verify(links, never()).findByServiceAccount(any());
        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean());
    }

    private MinecraftFriendService friendService() {
        MinecraftFriendService friends = new MinecraftFriendService(minecraft, tokens, serviceAccounts, links,
                mock(MinecraftAccountLimitMarker.class), mock(GetterConfigRepository.class), new ObjectMapper(),
                mock(ChannelEventPublisher.class));
        friends.setDebugRecording(new DebugRecording(registry, recorder));
        return friends;
    }

    @Test
    void addingAMember_answersWithTheWholeList_onlyItsOwnEntryIsRecorded() {
        debugged(bobLink);
        mojang.expect(requestTo(API + "/minecraft/profile/lookup/name/bob")).andRespond(withSuccess(
                "{\"id\":\"%s\",\"name\":\"bob\"}".formatted(BOB.replace("-", "")),
                MediaType.APPLICATION_JSON));
        mojang.expect(requestTo(API + "/friends")).andRespond(withSuccess("""
                {"friends":[{"profileId":"11111111111111111111111111111111","name":"alice"}],
                 "incomingRequests":[],
                 "outgoingRequests":[{"profileId":"%s","name":"bob"}],"empty":false}
                """.formatted(BOB_UNDASHED_UPPER), MediaType.APPLICATION_JSON));

        friendService().enroll(bobLink.getUser(), "bob");

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(bobLink.getUser().getId()), eq(Direction.OUT), raw.capture(),
                eq("minecraft.manage_friends"), eq(true));
        JsonNode body = json.readTree(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8));
        assertThat(body.path("friends")).isEmpty();
        assertThat(body.path("outgoingRequests")).hasSize(1);
        assertThat(new String(raw.getValue().requestBody(), StandardCharsets.UTF_8)).contains(BOB);
    }

    @Test
    void minecraftFriends_keepsOnlyTheMembersEntries() {
        debugged(bobLink);
        mojang.expect(requestTo(API + "/friends")).andRespond(withSuccess("""
                {"friends":[{"profileId":"11111111111111111111111111111111","name":"alice"},
                            {"profileId":"%s","name":"bob"}],
                 "incomingRequests":[{"profileId":"33333333333333333333333333333333","name":"stranger"}],
                 "outgoingRequests":[],"empty":false}
                """.formatted(BOB_UNDASHED_UPPER), MediaType.APPLICATION_JSON));
        friendService().syncFriendLinks();

        ArgumentCaptor<RawExchange> raw = ArgumentCaptor.forClass(RawExchange.class);
        verify(recorder).record(eq(bobLink.getUser().getId()), eq(Direction.OUT), raw.capture(),
                eq("minecraft.friends"), eq(true));
        verify(recorder, never()).record(eq(aliceLink.getUser().getId()), any(), any(), any(), anyBoolean());
        JsonNode body = json.readTree(new String(raw.getValue().responseBody(), StandardCharsets.UTF_8));
        assertThat(body.path("friends")).hasSize(1);
        assertThat(body.path("friends").get(0).path("name").asString()).isEqualTo("bob");
        assertThat(body.path("incomingRequests")).isEmpty();
        assertThat(body.path("outgoingRequests")).isEmpty();
    }
}
