package fr.enimaloc.catapult.service.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The system bot's token refresh and the per-channel "is the bot a mod" check. */
class SystemTwitchAccountServiceFlowsTest {

    private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    private static final String MODS_URL = "https://api.twitch.tv/helix/moderation/moderators?broadcaster_id=b-1&user_id=bot-1";

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    private final TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    private MockRestServiceServer twitch;
    private SystemTwitchAccountService service;
    private UserAccount bot;
    private OAuthToken botToken;
    private UserAccount streamer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        twitch = MockRestServiceServer.bindTo(builder).build();
        service = new SystemTwitchAccountService(accounts, tokens, encryption, builder.build());
        ReflectionTestUtils.setField(service, "twitchClientId", "client id");
        ReflectionTestUtils.setField(service, "twitchClientSecret", "secret");
        ReflectionTestUtils.setField(service, "modCacheTtlSeconds", 600L);

        bot = new UserAccount();
        bot.setId(UUID.randomUUID());
        bot.setTwitchId("bot-1");
        bot.setSystemAccount(true);
        botToken = new OAuthToken();
        botToken.setAccessToken("enc-access");
        botToken.setRefreshToken("enc-refresh");
        when(accounts.findBySystemAccountTrue()).thenReturn(Optional.of(bot));
        when(tokens.findByUserAndProvider(bot, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(botToken));
        when(encryption.decrypt("enc-access")).thenReturn("access");
        when(encryption.decrypt("enc-refresh")).thenReturn("refresh/token");
        when(encryption.encrypt(anyString())).thenAnswer(call -> "enc:" + call.getArgument(0));

        streamer = new UserAccount();
        streamer.setId(UUID.randomUUID());
        streamer.setTwitchId("b-1");
    }

    @Nested
    class Tokens {
        @Test
        void expiringTokens_areRefreshed_andTheNewOnesStored() {
            botToken.setExpiresAt(Instant.now().plusSeconds(30));
            twitch.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(content().string("grant_type=refresh_token&refresh_token=refresh%2Ftoken"
                            + "&client_id=client+id&client_secret=secret"))
                    .andRespond(withSuccess("{\"access_token\": \"new-access\", \"refresh_token\": \"new-refresh\","
                            + " \"expires_in\": 3600}", MediaType.APPLICATION_JSON));

            assertThat(service.getAccessToken()).isEqualTo("new-access");

            assertThat(botToken.getAccessToken()).isEqualTo("enc:new-access");
            assertThat(botToken.getRefreshToken()).isEqualTo("enc:new-refresh");
            assertThat(botToken.getExpiresAt()).isAfter(Instant.now().plusSeconds(3500));
            verify(tokens).save(botToken);
        }

        @Test
        void aRefreshWithoutNewRefreshToken_keepsTheOldOne() {
            twitch.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(
                    "{\"access_token\": \"new-access\", \"expires_in\": 60}", MediaType.APPLICATION_JSON));

            assertThat(service.refresh()).contains("new-access");
            assertThat(botToken.getRefreshToken()).isEqualTo("enc-refresh");
        }

        @Test
        void tokensWithoutExpiry_countAsExpired() {
            twitch.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

            assertThat(service.getAccessToken()).isNull();
            assertThat(service.tokenExpiry()).contains(Instant.EPOCH);
        }

        @Test
        void refreshFailures_yieldNothing() {
            twitch.expect(requestTo(TOKEN_URL)).andRespond(withServerError());
            twitch.expect(requestTo(TOKEN_URL)).andRespond(withSuccess());
            twitch.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\": \"x\"}", MediaType.APPLICATION_JSON));

            assertThat(service.refresh()).isEmpty();
            assertThat(service.refresh()).isEmpty();
            assertThat(service.refresh()).isEmpty();
            verify(tokens, never()).save(botToken);
        }

        @Test
        void anUnlinkedBot_hasNothingToRefresh() {
            bot.setTwitchId(null);
            assertThat(service.refresh()).isEmpty();
            assertThat(service.tokenExpiry()).isEmpty();

            when(accounts.findBySystemAccountTrue()).thenReturn(Optional.empty());
            assertThat(service.refresh()).isEmpty();
            assertThat(service.getSystemTwitchId()).isNull();
        }

        @Test
        void aMissingToken_cannotBeRefreshed() {
            when(tokens.findByUserAndProvider(bot, OAuthToken.Provider.TWITCH)).thenReturn(Optional.empty());

            assertThat(service.refresh()).isEmpty();
        }
    }

    @Nested
    class ModStatus {
        private void modsAnswer(String json) {
            twitch.expect(requestTo(MODS_URL))
                    .andExpect(header("Authorization", "Bearer streamer-token"))
                    .andExpect(header("Client-Id", "client id"))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        }

        @Test
        void isCachedPerChannel_untilInvalidated() {
            modsAnswer("{\"data\": [{\"user_id\": \"bot-1\"}]}");
            modsAnswer("{\"data\": []}");

            assertThat(service.check(streamer, "streamer-token").modded()).isTrue();
            assertThat(service.check(streamer, "streamer-token").modded()).isTrue();
            service.invalidateModStatus(streamer.getId());
            assertThat(service.check(streamer, "streamer-token").modded()).isFalse();
            twitch.verify();
        }

        @Test
        void expiredEntries_areRefetched() {
            ReflectionTestUtils.setField(service, "modCacheTtlSeconds", -1L);
            modsAnswer("{}");
            modsAnswer("{\"data\": []}");

            assertThat(service.check(streamer, "streamer-token").modded()).isFalse();
            assertThat(service.check(streamer, "streamer-token").modded()).isFalse();
            twitch.verify();
        }

        @Test
        void failuresOrAnUnlinkedBot_meanNotModded() {
            twitch.expect(requestTo(MODS_URL)).andRespond(withServerError());
            assertThat(service.check(streamer, "streamer-token").modded()).isFalse();

            service.invalidateModStatus(streamer.getId());
            when(accounts.findBySystemAccountTrue()).thenReturn(Optional.empty());
            assertThat(service.check(streamer, "streamer-token").modded()).isFalse();
            twitch.verify();
        }
    }
}
