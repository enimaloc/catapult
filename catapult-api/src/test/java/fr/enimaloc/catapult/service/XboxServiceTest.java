package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class XboxServiceTest {

    // Jackson 3, comme les converters HTTP de Boot 4 : valide le contrat JSON réel
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    @InjectMocks private XboxService service;

    @BeforeEach
    void setupRestClientChain() {
        doReturn(postSpec).when(restClient).post();
        doReturn(bodySpec).when(postSpec).uri(any(URI.class));
        // body(Object) : matcher explicite sinon Mockito choisit l'overload body(Class) -> NPE
        doReturn(bodySpec).when(bodySpec).body(any(Object.class));
        doReturn(bodySpec).when(bodySpec).contentType(any(MediaType.class));
        doReturn(bodySpec).when(bodySpec).accept(any(MediaType.class));
        doReturn(responseSpec).when(bodySpec).retrieve();
    }

    @Test
    void getXboxToken_postsToUserAuthenticateEndpoint() {
        service.getXboxToken("d=ticket");

        verify(postSpec).uri(URI.create("https://user.auth.xboxlive.com/user/authenticate"));
        verify(bodySpec).contentType(MediaType.APPLICATION_JSON);
        verify(bodySpec).accept(MediaType.APPLICATION_JSON);
    }

    @Test
    void getXboxToken_payloadSerializesWithPascalCaseKeys() throws Exception {
        service.getXboxToken("d=my-rps-ticket");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec).body(payload.capture());

        var json = mapper.readTree(mapper.writeValueAsString(payload.getValue()));
        assertThat(json.at("/Properties/AuthMethod").asText()).isEqualTo("RPS");
        assertThat(json.at("/Properties/SiteName").asText()).isEqualTo("user.auth.xboxlive.com");
        assertThat(json.at("/Properties/RpsTicket").asText()).isEqualTo("d=my-rps-ticket");
        assertThat(json.at("/RelyingParty").asText()).isEqualTo("http://auth.xboxlive.com");
        assertThat(json.at("/TokenType").asText()).isEqualTo("JWT");
    }

    @Test
    void getXboxToken_returnsBodyFromResponse() {
        var token = new XboxService.Token(
                Instant.parse("2026-07-07T10:00:00Z"),
                Instant.parse("2026-07-08T02:00:00Z"),
                "jwt-token",
                new XboxService.DisplayClaims(new XboxService.DisplayClaims.Xui[]{
                        new XboxService.DisplayClaims.Xui("user-hash", "1234567890")
                })
        );
        doReturn(token).when(responseSpec).body(XboxService.Token.class);

        assertThat(service.getXboxToken("d=ticket")).isSameAs(token);
    }

    @Test
    void xboxToken_deserializesFromRealXboxResponseShape() throws Exception {
        // Forme documentée de la réponse user.auth.xboxlive.com : PascalCase sauf xui/uhs
        var json = """
                {
                  "IssueInstant": "2026-07-07T19:52:08.4463796Z",
                  "NotAfter": "2026-07-08T11:52:08.4463796Z",
                  "Token": "eyJhbGciOi...token",
                  "DisplayClaims": {
                    "xui": [{"uhs": "1234567890123456"}]
                  }
                }
                """;

        var token = mapper.readValue(json, XboxService.Token.class);

        assertThat(token.token()).isEqualTo("eyJhbGciOi...token");
        assertThat(token.issueInstant()).isEqualTo(Instant.parse("2026-07-07T19:52:08.4463796Z"));
        assertThat(token.notAfter()).isEqualTo(Instant.parse("2026-07-08T11:52:08.4463796Z"));
        assertThat(token.displayClaims().xui()).hasSize(1);
        assertThat(token.displayClaims().xui()[0].uhs()).isEqualTo("1234567890123456");
    }
}
