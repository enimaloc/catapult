package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.twitchat.TwitchatActionType;
import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.twitchat.TwitchatActionToken;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.twitchat.TwitchatActionTokenRepository;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.BindingService;
import fr.enimaloc.catapult.service.binding.CustomBindingActivations;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreGateService;
import fr.enimaloc.catapult.service.binding.RaidRestoreHold;
import fr.enimaloc.catapult.service.binding.StreamStateService;
import fr.enimaloc.catapult.service.category.CategoryApplier;
import fr.enimaloc.catapult.service.category.CategoryChain;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import fr.enimaloc.catapult.service.twitch.EventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchCategoryService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import fr.enimaloc.catapult.service.twitch.TwitchTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * A Twitchat revert whose Twitch token is dead, in real transactions: the revert's transaction rolls back (the
 * action answers an error), and the bot pause it caused stays committed.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:twitchat_revert_token_failure;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TwitchatActionExecutor.class, TwitchatActionTokenService.class, BotToggleService.class,
        CategoryChain.class, CategoryApplier.class, RaidRestoreHold.class, TwitchServiceImpl.class,
        ExternalApiObservations.class, TwitchatRevertTokenFailureTest.Wiring.class})
class TwitchatRevertTokenFailureTest {

    @TestConfiguration
    static class Wiring {
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer twitch = MockRestServiceServer.bindTo(builder).build();

        @Bean RestClient restClient() {
            return builder.build();
        }

        @Bean MockRestServiceServer twitch() {
            return twitch;
        }

        @Bean ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }

        @Bean MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired TwitchatActionExecutor executor;
    @Autowired UserAccountRepository accounts;
    @Autowired OAuthTokenRepository oauthTokens;
    @Autowired TwitchatActionTokenRepository actionTokens;
    @Autowired MockRestServiceServer twitch;

    @MockitoBean TwitchTokenService twitchTokenService;
    @MockitoBean TwitchCategoryService twitchCategoryService;
    @MockitoBean CatapultCategoryChangeStateService categoryChangeStateService;
    @MockitoBean TwitchChatService twitchChatService;
    @MockitoBean EventSubService eventSubService;
    @MockitoBean ChannelEventPublisher channelEventPublisher;
    @MockitoBean TwitchatEventDispatcher twitchatEventDispatcher;
    @MockitoBean BindingService bindingService;
    @MockitoBean CustomBindingActivations customBindingActivations;
    @MockitoBean StreamStateService streamStateService;
    @MockitoBean GameStateService gameStateService;
    @MockitoBean RaidRestoreGateService raidRestoreGateService;
    @MockitoBean GameBindingLookup gameBindingLookup;

    @AfterEach
    void clean() {
        actionTokens.deleteAll();
        oauthTokens.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void revert_withADeadToken_keepsTheBotPaused_whileTheActionFails() {
        UserAccount user = new UserAccount();
        user.setTwitchUsername("streamer");
        user.setTwitchId("12345");
        user.setBotEnabled(true);
        user.setStatus(UserAccount.Status.ACTIVE);
        user = accounts.save(user);
        OAuthToken token = new OAuthToken();
        token.setUser(user);
        token.setProvider(OAuthToken.Provider.TWITCH);
        token.setAccessToken("encrypted");
        oauthTokens.save(token);
        TwitchatActionToken action = new TwitchatActionToken();
        action.setToken(UUID.randomUUID());
        action.setUserId(user.getId());
        action.setActionType(TwitchatActionType.REVERT_CATEGORY);
        action.setPayloadJson("{\"gameId\":\"509658\",\"gameName\":\"Just Chatting\"}");
        action.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        actionTokens.save(action);

        when(twitchTokenService.resolveAccessToken(any(), any())).thenReturn("expired");
        when(twitchTokenService.refreshAccessToken(any(), any())).thenReturn(null);
        twitch.expect(ExpectedCount.once(), requestTo("https://api.twitch.tv/helix/channels?broadcaster_id=12345"))
                .andRespond(withUnauthorizedRequest());

        UUID actionId = action.getToken();
        assertThrows(HttpClientErrorException.class, () -> executor.execute(actionId));

        twitch.verify();
        assertThat(accounts.findById(user.getId())).hasValueSatisfying(
                saved -> assertThat(saved.isBotEnabled()).as("the pause survived the rollback").isFalse());
        // The token's consumption is durable by design (its own transaction), whatever the action did after it
        assertThat(actionTokens.findById(actionId)).hasValueSatisfying(
                saved -> assertThat(saved.getConsumedAt()).isNotNull());
    }
}
