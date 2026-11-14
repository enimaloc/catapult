package fr.enimaloc.catapult.service.ankama.haapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnkamaHaapiClientTest {

    private static final String HAAPI = "https://haapi.test/v5/";
    private static final String AUTH = "https://auth.test/";
    private static final String HM1 = "0123456789abcdef0123456789abcdef";

    private MockRestServiceServer server;
    private AnkamaHaapiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnkamaHaapiClient(builder, HAAPI, AUTH);
    }

    private static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }

    @Test
    void exchangeCode_postsThePkceForm_andMapsTheTokens() {
        server.expect(requestTo(AUTH + "auth/token")).andExpect(method(HttpMethod.POST))
                .andExpect(header("User-Agent", "Zaap 3.15.15"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("grant_type=authorization_code&code=abc"
                        + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A9001%2Fauthorized&client_id=102&code_verifier=ver"))
                .andRespond(withSuccess("{\"access_token\":\"K\",\"refresh_token\":\"R\",\"exp\":1}", json()));

        assertThat(client.exchangeCode("abc", "ver")).isEqualTo(new AnkamaHaapiClient.Tokens("K", "R"));
        server.verify();
    }

    @Test
    void account_sendsTheApiKeyHeader_andMapsIdNicknameTag() {
        server.expect(requestTo(HAAPI + "Account/Account")).andExpect(header("APIKEY", "K"))
                .andExpect(header("User-Agent", "Zaap 3.15.15"))
                .andRespond(withSuccess("{\"id\":190683866,\"nickname\":\"Catapult-1\",\"tag\":\"5091\",\"x\":1}", json()));

        AnkamaHaapiClient.Account account = client.account("K");

        assertThat(account.id()).isEqualTo(190683866L);
        assertThat(account.nickname()).isEqualTo("Catapult-1");
        assertThat(account.tag()).isEqualTo("5091");
        assertThat(account.security()).isEmpty();
        assertThat(account.needsShield()).isFalse();
    }

    @Test
    void account_needsShield_whenSecurityContainsShield() {
        server.expect(requestTo(HAAPI + "Account/Account")).andRespond(withSuccess(
                "{\"id\":1,\"nickname\":\"n\",\"tag\":\"t\",\"security\":[\"SHIELD\"]}", json()));

        assertThat(client.account("K").needsShield()).isTrue();
    }

    @Test
    void refresh_returnsTheNewRefreshToken() {
        server.expect(requestTo(HAAPI + "Api/RefreshApiKey")).andExpect(method(HttpMethod.POST))
                .andExpect(header("APIKEY", "K"))
                .andExpect(content().string("refresh_token=old&long_life_token=true&shop_key=ZAAP"
                        + "&payment_mode=OK&lang=fr"))
                .andRespond(withSuccess("{\"key\":\"K\",\"refresh_token\":\"new\"}", json()));

        assertThat(client.refresh("K", "old")).isEqualTo("new");
    }

    @Test
    void chatToken_asksForGame99() {
        server.expect(requestTo(HAAPI + "Account/CreateToken?game=99")).andExpect(header("APIKEY", "K"))
                .andRespond(withSuccess("{\"token\":\"T\"}", json()));

        assertThat(client.chatToken("K")).isEqualTo("T");
    }

    @Test
    void chatToken_sendsTheCertificate_whenThereIsOne() throws Exception {
        AnkamaHaapiClient.Certificate certificate = certificate("plain-text");
        String hash = AnkamaHaapiClient.certificateHash(HM1, certificate);
        server.expect(requestTo(HAAPI + "Account/CreateToken?game=99&certificate_id=7&certificate_hash=" + hash))
                .andRespond(withSuccess("{\"token\":\"T\"}", json()));

        assertThat(client.chatToken("K", certificate, HM1)).isEqualTo("T");
        server.verify();
    }

    @Test
    void chatToken_aShieldRefusal_isAnAnkamaShieldException() {
        server.expect(requestTo(HAAPI + "Account/CreateToken?game=99")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(json()).body("{\"message\":\"Shield certificate required\"}"));

        assertThatThrownBy(() -> client.chatToken("K")).isInstanceOf(AnkamaShieldException.class);
    }

    @Test
    void refusedKey_isAnAnkamaAuthException() {
        server.expect(requestTo(HAAPI + "Account/Account")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(HAAPI + "Account/CreateToken?game=99")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.account("K")).isInstanceOf(AnkamaAuthException.class);
        assertThatThrownBy(() -> client.chatToken("K")).isInstanceOf(AnkamaAuthException.class);
    }

    @Test
    void validateShieldCode_sendsHm1AndItsReverse_andMapsTheCertificate() {
        String hm2 = new StringBuilder(HM1).reverse().toString();
        server.expect(requestTo(HAAPI + "Shield/ValidateCode?game_id=102&code=ABC123&hm1=" + HM1 + "&hm2=" + hm2
                + "&name=launcher-catapult")).andExpect(header("APIKEY", "K"))
                .andRespond(withSuccess("{\"id\":7,\"encodedCertificate\":\"Zm9v\"}", json()));

        assertThat(client.validateShieldCode("K", "ABC123", HM1))
                .isEqualTo(new AnkamaHaapiClient.Certificate(7, "Zm9v"));
    }

    @Test
    void wrongShieldCode_isAnAnkamaShieldException_withAnkamasMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(HAAPI + "Shield/ValidateCode")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(json())
                        .body("{\"message\":\"Invalid security code\"}"));

        assertThatThrownBy(() -> client.validateShieldCode("K", "bad", HM1))
                .isInstanceOf(AnkamaShieldException.class).hasMessage("Invalid security code");
    }

    @Test
    void sendShieldCode_returnsTheMailDomain() {
        server.expect(requestTo(HAAPI + "Shield/SecurityCode?transportType=EMAIL"))
                .andRespond(withSuccess("{\"domain\":\"catapult.enimaloc.fr\"}", json()));

        assertThat(client.sendShieldCode("K")).isEqualTo("catapult.enimaloc.fr");
    }

    @Test
    void certificateHash_matchesAReferenceComputedWithOpenssl() throws Exception {
        AnkamaHaapiClient.Certificate certificate = certificate("some-secret-text");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((HM1 + "some-secret-text").getBytes(StandardCharsets.UTF_8)));

        assertThat(AnkamaHaapiClient.certificateHash(HM1, certificate)).isEqualTo(expected);
    }

    /** Encrypts {@code text} the way Ankama does: AES-256-ECB/PKCS#7 under hm2, base64. */
    private static AnkamaHaapiClient.Certificate certificate(String text) throws Exception {
        byte[] key = new StringBuilder(HM1).reverse().toString().getBytes(StandardCharsets.US_ASCII);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return new AnkamaHaapiClient.Certificate(7,
                Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8))));
    }
}
