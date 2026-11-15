package fr.enimaloc.catapult.service.epic.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EpicAccountClientTest {

    private static final String URL = "https://account.test/oauth/token";
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private MockRestServiceServer server;
    private EpicAccountClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EpicAccountClient(builder, URL, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exchangeCode_postsTheLauncherClientWithEg1_andMapsTheTokens() {
        String basic = Base64.getEncoder().encodeToString(
                "34a02cf8f4414e29b15921876da36f9a:daafbccc737745039dffe53d94fc76cf".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + basic))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(new org.springframework.util.LinkedMultiValueMap<>(java.util.Map.of(
                        "grant_type", java.util.List.of("authorization_code"),
                        "code", java.util.List.of("the-code"),
                        "token_type", java.util.List.of("eg1")))))
                .andRespond(withSuccess("""
                        {"access_token":"at","expires_in":7200,"token_type":"bearer","refresh_token":"rt",
                         "refresh_expires":31540000,"account_id":"27871f9dd56446c8a1f1d3db1aaa8a7e",
                         "client_id":"34a02cf8f4414e29b15921876da36f9a","displayName":"Catapult-1"}
                        """, MediaType.APPLICATION_JSON));

        EpicAccountClient.Tokens tokens = client.exchangeCode("the-code");

        assertThat(tokens.accessToken()).isEqualTo("at");
        assertThat(tokens.refreshToken()).isEqualTo("rt");
        assertThat(tokens.accountId()).isEqualTo("27871f9dd56446c8a1f1d3db1aaa8a7e");
        assertThat(tokens.displayName()).isEqualTo("Catapult-1");
        assertThat(tokens.accessExpiresAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(tokens.refreshExpiresAt()).isEqualTo(NOW.plusSeconds(31540000));
        assertThat(Duration.between(NOW, tokens.refreshExpiresAt()).toDays()).isBetween(364L, 366L);
        server.verify();
    }

    @Test
    void refresh_postsTheRefreshGrant() {
        server.expect(requestTo(URL)).andExpect(content().string(
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("grant_type=refresh_token"),
                                org.hamcrest.Matchers.containsString("refresh_token=old"),
                                org.hamcrest.Matchers.containsString("token_type=eg1"))))
                .andRespond(withSuccess("{\"access_token\":\"at2\",\"expires_in\":60,\"refresh_token\":\"rt2\","
                        + "\"refresh_expires\":120,\"account_id\":\"a\",\"displayName\":\"n\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.refresh("old").refreshToken()).isEqualTo("rt2");
    }

    @Test
    void usedCode_isCodeExpired() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errorCode\":"
                        + "\"errors.com.epicgames.account.oauth.authorization_code_not_found\","
                        + "\"errorMessage\":\"Sorry the authorization code you supplied was not found\","
                        + "\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> client.exchangeCode("used"))
                .isInstanceOfSatisfying(EpicAuthException.class,
                        e -> assertThat(e.reason()).isEqualTo(EpicAuthException.Reason.CODE_EXPIRED));
    }

    @Test
    void refusedRefresh_isRefreshRefused() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errorCode\":"
                        + "\"errors.com.epicgames.account.auth_token.invalid_refresh_token\","
                        + "\"error\":\"invalid_grant\"}"));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> client.refresh("old"))
                    .isInstanceOfSatisfying(EpicAuthException.class,
                            e -> assertThat(e.reason()).isEqualTo(EpicAuthException.Reason.REFRESH_REFUSED));
        }
    }

    @Test
    void errorBodies_areNeverCopiedWithTokens() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"errorCode\":"
                        + "\"errors.com.epicgames.account.oauth.authorization_code_not_found\","
                        + "\"errorMessage\":\"code secret-code refresh secret-refresh\"}"));

        assertThatThrownBy(() -> client.exchangeCode("secret-code"))
                .hasMessageNotContaining("secret-code").hasMessageNotContaining("secret-refresh");
    }

    @Test
    void otherFailures_areNotAuthFailures() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.refresh("old"))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .isNotInstanceOf(EpicAuthException.class);
    }
}
