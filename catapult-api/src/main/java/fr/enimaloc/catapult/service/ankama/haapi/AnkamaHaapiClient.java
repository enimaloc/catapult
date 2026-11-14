package fr.enimaloc.catapult.service.ankama.haapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal client of Ankama's launcher API (HAAPI) and OAuth endpoint, written from the observed protocol. */
@Component
@ConditionalOnBooleanProperty("ankama.enabled")
public class AnkamaHaapiClient {

    private static final String USER_AGENT = "Zaap 3.15.15";
    private static final String REDIRECT_URI = "http://127.0.0.1:9001/authorized";
    private static final String CLIENT_ID = "102";

    public record Tokens(String key, String refreshToken) {
    }

    public record Account(long id, String nickname, String tag, List<String> security) {
        public Account {
            security = security == null ? List.of() : List.copyOf(security);
        }

        public boolean needsShield() {
            return security.contains("SHIELD");
        }
    }

    public record Certificate(long id, String encodedCertificate) {
    }

    private record Reply(int status, JsonNode body) {
    }

    private final RestClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String haapiUrl;
    private final String authUrl;

    public AnkamaHaapiClient(RestClient.Builder builder,
                             @Value("${ankama.haapi-url:https://haapi.ankama.com/json/Ankama/v5/}") String haapiUrl,
                             @Value("${ankama.auth-url:https://auth.ankama.com/}") String authUrl) {
        this.client = builder.clone().defaultHeader("User-Agent", USER_AGENT).build();
        this.haapiUrl = haapiUrl;
        this.authUrl = authUrl;
    }

    public Tokens exchangeCode(String code, String verifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("client_id", CLIENT_ID);
        form.add("code_verifier", verifier);
        JsonNode body = ok(post(authUrl + "token", form));
        return new Tokens(text(body, "access_token"), text(body, "refresh_token"));
    }

    public Account account(String key) {
        JsonNode body = ok(get(haapiUrl + "Account/Account", key, Map.of()));
        List<String> security = new ArrayList<>();
        body.path("security").forEach(node -> security.add(node.asString()));
        return new Account(body.path("id").asLong(), text(body, "nickname"), text(body, "tag"), security);
    }

    /** Returns the new refresh token. */
    public String refresh(String key, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("long_life_token", "true");
        form.add("shop_key", "ZAAP");
        form.add("payment_mode", "OK");
        form.add("lang", "fr");
        Reply reply = client.post().uri(haapiUrl + "Api/RefreshApiKey").header("APIKEY", key)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).exchange(this::read);
        return text(ok(reply), "refresh_token");
    }

    /** Asks for a chat token without a Shield certificate. */
    public String chatToken(String key) {
        return chatToken(key, null, null);
    }

    /**
     * Asks for a chat token; {@code certificate} (nullable) is the Shield certificate, {@code hm1} the
     * per-account secret it was issued for (needed for the certificate proof, ignored without a certificate).
     */
    public String chatToken(String key, Certificate certificate, String hm1) {
        Reply reply;
        if (certificate == null) {
            reply = get(haapiUrl + "Account/CreateToken?game=99", key, Map.of());
        } else {
            reply = get(haapiUrl + "Account/CreateToken?game=99&certificate_id={id}&certificate_hash={hash}", key,
                    Map.of("id", String.valueOf(certificate.id()), "hash", certificateHash(hm1, certificate)));
        }
        int status = reply.status();
        if (status >= 400 && status < 500) {
            String reason = (text(reply.body(), "message") + " " + text(reply.body(), "reason"))
                    .toUpperCase(Locale.ROOT);
            if (reason.contains("SHIELD") || reason.contains("CERTIFICATE")) {
                throw new AnkamaShieldException("Ankama requires a valid Shield certificate");
            }
        }
        return text(ok(reply), "token");
    }

    public String sendShieldCode(String key) {
        return text(ok(get(haapiUrl + "Shield/SecurityCode?transportType=EMAIL", key, Map.of())), "domain");
    }

    public Certificate validateShieldCode(String key, String code, String hm1) {
        String hm2 = new StringBuilder(hm1).reverse().toString();
        Reply reply = get(haapiUrl + "Shield/ValidateCode?game_id=102&code={code}&hm1={hm1}&hm2={hm2}"
                + "&name=launcher-catapult", key, Map.of("code", code, "hm1", hm1, "hm2", hm2));
        if (reply.status() >= 400 && reply.status() < 500) {
            String message = text(reply.body(), "message");
            if (!message.isEmpty()) throw new AnkamaShieldException(message);
        }
        JsonNode body = ok(reply);
        return new Certificate(body.path("id").asLong(), text(body, "encodedCertificate"));
    }

    /** {@code hex(sha256(hm1 + decrypt(certificate)))}, the proof Ankama expects next to the certificate id. */
    public static String certificateHash(String hm1, Certificate certificate) {
        try {
            byte[] key = new StringBuilder(hm1).reverse().toString().getBytes(StandardCharsets.US_ASCII);
            // ECB is imposed by Ankama's certificate format
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            String decrypted = new String(cipher.doFinal(Base64.getDecoder().decode(certificate.encodedCertificate())),
                    StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((hm1 + decrypted).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("unusable Shield certificate");
        }
    }

    private Reply get(String url, String key, Map<String, String> vars) {
        return client.get().uri(url, vars).header("APIKEY", key).exchange(this::read);
    }

    private Reply post(String url, MultiValueMap<String, String> form) {
        return client.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .exchange(this::read);
    }

    private Reply read(org.springframework.http.HttpRequest request,
                       RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        byte[] raw = response.getBody().readAllBytes();
        JsonNode body;
        try {
            body = raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (RuntimeException e) {
            body = mapper.createObjectNode();
        }
        return new Reply(status, body);
    }

    private JsonNode ok(Reply reply) {
        int status = reply.status();
        if (status >= 200 && status < 300) return reply.body();
        if (status == 401 || status == 403) throw new AnkamaAuthException("Ankama refused the credentials");
        throw new RestClientException("Ankama answered HTTP " + status);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asString();
    }
}
