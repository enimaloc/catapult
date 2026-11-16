package fr.enimaloc.catapult.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** How the authorization-code token request authenticates the client, per registration. */
class OAuth2AuthorizationCodeTokenClientTest {

    private static final String SECRET = "ab/cd+ef=gh";

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> client =
        new OAuth2ClientConfig().authorizationCodeTokenResponseClient();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] json = "{\"access_token\":\"tok\",\"token_type\":\"bearer\",\"expires_in\":60}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OAuth2AuthorizationCodeGrantRequest request(String registrationId, ClientAuthenticationMethod method) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
            .clientId("the-id")
            .clientSecret(SECRET)
            .clientAuthenticationMethod(method)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("https://api.test/login/oauth2/code/" + registrationId)
            .authorizationUri("https://provider.test/authorize")
            .tokenUri("http://127.0.0.1:" + server.getAddress().getPort() + "/token")
            .build();
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://provider.test/authorize")
            .clientId("the-id")
            .redirectUri(registration.getRedirectUri())
            .state("st")
            .build();
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success("code")
            .redirectUri(registration.getRedirectUri())
            .state("st")
            .build();
        return new OAuth2AuthorizationCodeGrantRequest(registration,
            new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void epic_sendsTheRawSecretInTheBasicHeader() {
        assertThat(client.getTokenResponse(request("epic", ClientAuthenticationMethod.CLIENT_SECRET_BASIC))
            .getAccessToken().getTokenValue()).isEqualTo("tok");

        assertThat(authorization.get()).isEqualTo(basic("the-id", SECRET));
    }

    @Test
    void battlenet_keepsSpringsUrlEncodedBasicHeader() {
        client.getTokenResponse(request("battlenet", ClientAuthenticationMethod.CLIENT_SECRET_BASIC));

        assertThat(authorization.get()).isEqualTo(basic("the-id", "ab%2Fcd%2Bef%3Dgh"));
    }

    @Test
    void twitch_keepsCredentialsInTheBody() {
        client.getTokenResponse(request("twitch", ClientAuthenticationMethod.CLIENT_SECRET_POST));

        assertThat(authorization.get()).isNull();
        assertThat(body.get()).contains("client_id=the-id").contains("client_secret=ab%2Fcd%2Bef%3Dgh");
    }
}
