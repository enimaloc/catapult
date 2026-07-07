package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Optional;

/**
 * Client OAuth Microsoft (comptes personnels) pour les comptes de service Minecraft.
 * Deux flux : device-code (enrôlement one-shot en admin) et refresh_token (renouvellement).
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("minecraft.enabled")
public class MsaAuthClient {

    public static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    public static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    public static final String DEVICE_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    public static final String SCOPE = "XboxLive.signin offline_access";

    private final RestClient restClient;
    private final String clientId;

    public MsaAuthClient(RestClient restClient, @Value("${minecraft.msa-client-id:}") String clientId) {
        this.restClient = restClient;
        this.clientId = clientId;
    }

    public MsaTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("scope", SCOPE);
        return postForm(URI.create(TOKEN_URL), form);
    }

    public DeviceCodeStart startDeviceCode() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("scope", SCOPE);
        return restClient.post()
                .uri(URI.create(DEVICE_CODE_URL))
                .body(form)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(DeviceCodeStart.class);
    }

    /** Vide tant que l'utilisateur n'a pas validé le code ({@code authorization_pending}). */
    public Optional<MsaTokens> pollDeviceCode(String deviceCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        form.add("device_code", deviceCode);
        try {
            return Optional.of(postForm(URI.create(DEVICE_TOKEN_URL), form));
        } catch (HttpClientErrorException e) {
            if (e.getResponseBodyAsString().matches("(?s).*\"error\"\\s*:\\s*\"authorization_pending\".*")) {
                return Optional.empty();
            }
            throw new MsaAuthException("Device code flow failed: " + e.getResponseBodyAsString(), e);
        }
    }

    private MsaTokens postForm(URI uri, MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(uri)
                .body(form)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MsaTokens.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsaTokens(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceCodeStart(
            @JsonProperty("device_code") String deviceCode,
            @JsonProperty("user_code") String userCode,
            @JsonProperty("verification_uri") String verificationUri,
            int interval,
            @JsonProperty("expires_in") int expiresIn
    ) {}

    public static class MsaAuthException extends RuntimeException {
        public MsaAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
