package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("${xbox.enabled:false} or ${minecraft.enabled:false}")
public class XboxService {
    public static final String USER_AUTH_URL = "https://user.auth.xboxlive.com";
    public static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com";
    private final RestClient restClient;

    public Token getXboxToken(String rpsTicket) {
        return getXboxToken(new PropertiesRequest<>(
                new UserAuthXboxServicePayload(rpsTicket),
                "http://auth.xboxlive.com",
                "JWT"
        ));
    }

    public Token getXboxToken(PropertiesRequest<UserAuthXboxServicePayload> ticket) {
        return restClient.post()
                .uri(URI.create(USER_AUTH_URL + "/user/authenticate"))
                .body(ticket)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Token.class);
    }

    public static final String MINECRAFT_RELYING_PARTY = "rp://api.minecraftservices.com/";
    public static final String XBOX_LIVE_RELYING_PARTY = "http://xboxlive.com";

    public Token getXstsToken(Token... xboxTokens) {
        return getXstsToken(Arrays.stream(xboxTokens).map(Token::token).toArray(String[]::new));
    }

    public Token getXstsToken(String... tokens) {
        return getXstsToken(MINECRAFT_RELYING_PARTY, tokens);
    }

    public Token getXstsToken(String relyingParty, Token... xboxTokens) {
        return getXstsToken(relyingParty, Arrays.stream(xboxTokens).map(Token::token).toArray(String[]::new));
    }

    public Token getXstsToken(String relyingParty, String... tokens) {
        return getXstsToken(new PropertiesRequest<>(
                new XstsAuthXboxServicePayload(tokens),
                relyingParty,
                "JWT"
        ));
    }

    private Token getXstsToken(PropertiesRequest<XstsAuthXboxServicePayload> tokens) {
        return restClient.post()
                .uri(URI.create(XSTS_AUTH_URL + "/xsts/authorize"))
                .body(tokens)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Token.class);
    }

    // L'API Xbox Live exige des clés JSON en PascalCase, sauf xui/uhs (minuscules)
    public record UserAuthXboxServicePayload(
                @JsonProperty("AuthMethod") String authMethod,
                @JsonProperty("SiteName") String siteName,
                @JsonProperty("RpsTicket") String rpsTicket
        ) {
        public UserAuthXboxServicePayload(String rpsTicket) {
                this("RPS", "user.auth.xboxlive.com", rpsTicket);
            }
    }

    public record XstsAuthXboxServicePayload(
                @JsonProperty("SandboxId") String sandboxId,
                @JsonProperty("UserTokens") String[] tokens
        ) {
        public XstsAuthXboxServicePayload(String[] tokens) {
                this("RETAIL", tokens);
            }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Token(
            @JsonProperty("IssueInstant") Instant issueInstant,
            @JsonProperty("NotAfter") Instant notAfter,
            @JsonProperty("Token") String token,
            @JsonProperty("DisplayClaims") DisplayClaims displayClaims
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisplayClaims(Xui[] xui) {
        // xid absent des réponses scopées Minecraft, présent pour la relying party Xbox Live
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Xui(String uhs, String xid) {}
    }

    public record PropertiesRequest<T>(
            @JsonProperty("Properties") T properties,
            @JsonProperty("RelyingParty") String relyingParty,
            @JsonProperty("TokenType") String tokenType
    ){}
}
