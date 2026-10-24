package fr.enimaloc.catapult.service.debug;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DebugRedactorTest {

    private final DebugRedactor redactor = new DebugRedactor();

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void bearer_isMasked() {
        HttpHeaders h = new HttpHeaders();
        h.add("Authorization", "Bearer abc");
        h.add("Accept", "application/json");
        assertThat(redactor.headers(h, false)).contains("Authorization: [masqué]").contains("Accept: application/json")
                .doesNotContain("abc");
    }

    @Test
    void queryKey_isMasked() {
        assertThat(redactor.url(URI.create("https://x.test/a?key=ABC&steamids=1"), false))
                .isEqualTo("https://x.test/a?key=[masqué]&steamids=1");
    }

    @Test
    void formClientSecret_isMasked() {
        assertThat(redactor.body(b("client_secret=s3cr3t&grant_type=x"), MediaType.APPLICATION_FORM_URLENCODED, false))
                .isEqualTo("client_secret=[masqué]&grant_type=x");
    }

    @Test
    void nestedJsonToken_isMasked() {
        String out = redactor.body(b("{\"data\":[{\"access_token\":\"t\",\"n\":1}]}"), MediaType.APPLICATION_JSON, false);
        assertThat(out).contains("\"access_token\":\"[masqué]\"").contains("\"n\":1").doesNotContain("\"t\"");
    }

    @Test
    void secretInMalformedJson_isStillMasked() {
        String out = redactor.body(b("{\"a\":1}\n{\"Device_Code\": \"dc123\", \"token\":abc, \"n\":2"), MediaType.APPLICATION_JSON, false);
        assertThat(out).doesNotContain("dc123").doesNotContain("abc").contains("\"n\":2");
    }

    @Test
    void deviceCode_isMaskedInFormAndJson() {
        assertThat(redactor.body(b("device_code=zzz&grant_type=x"), MediaType.APPLICATION_FORM_URLENCODED, false))
                .isEqualTo("device_code=[masqué]&grant_type=x");
        assertThat(redactor.body(b("{\"device_code\":\"zzz\"}"), MediaType.APPLICATION_JSON, false)).doesNotContain("zzz");
    }

    @Test
    void unsupportedCharset_fallsBackToUtf8() {
        MediaType bad = new MediaType("text", "plain") {
            @Override public java.nio.charset.Charset getCharset() { throw new java.nio.charset.UnsupportedCharsetException("x"); }
        };
        assertThat(redactor.body(b("héllo"), bad, false)).isEqualTo("héllo");
    }

    @Test
    void otherContentType_isUnchanged() {
        assertThat(redactor.body(b("token=abc"), MediaType.TEXT_PLAIN, false)).isEqualTo("token=abc");
    }

    @Test
    void reveal_keepsEverything() {
        HttpHeaders h = new HttpHeaders();
        h.add("Authorization", "Bearer abc");
        assertThat(redactor.headers(h, true)).contains("Bearer abc");
        assertThat(redactor.url(URI.create("https://x.test/a?key=ABC"), true)).endsWith("key=ABC");
        assertThat(redactor.body(b("{\"token\":\"t\"}"), MediaType.APPLICATION_JSON, true)).isEqualTo("{\"token\":\"t\"}");
    }

    @Test
    void longBody_isTruncated() {
        String out = redactor.body(b("a".repeat(70_000)), MediaType.TEXT_PLAIN, false);
        assertThat(out).isEqualTo("a".repeat(65_536) + "…(+4464 chars)");
    }

    @Test
    void freeText_secretParamsAndJsonFieldsAreMasked_unlessRevealed() {
        String error = "IOException: GET https://x.test/a?access_token=tok&monkey=ok&key=K1 failed "
                + "{\"client_secret\":\"xyz\"} openid.sig=SIG";
        assertThat(redactor.text(error, false))
                .isEqualTo("IOException: GET https://x.test/a?access_token=[masqué]&monkey=ok&key=[masqué] failed "
                        + "{\"client_secret\":\"[masqué]\"} openid.sig=[masqué]");
        assertThat(redactor.text(error, true)).isEqualTo(error);
    }

    @Test
    void freeText_isTruncated_andNullStaysNull() {
        assertThat(redactor.text("x".repeat(DebugRedactor.MAX_BODY_CHARS + 3), true)).endsWith("…(+3 chars)");
        assertThat(redactor.text(null, false)).isNull();
    }

    // ------------------------------------------------- one case per credential catapult-api sends or receives

    private static HttpHeaders header(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.add(name, value);
        return h;
    }

    private String json(String body) {
        return redactor.body(b(body), MediaType.APPLICATION_JSON, false);
    }

    private String form(String body) {
        return redactor.body(b(body), MediaType.APPLICATION_FORM_URLENCODED, false);
    }

    @Test
    void gitlab_privateTokenHeader_isMasked_whateverItsCase() {
        assertThat(redactor.headers(header("PRIVATE-TOKEN", "glpat-abc123"), false))
                .isEqualTo("PRIVATE-TOKEN: [masqué]");
        assertThat(redactor.headers(header("private-token", "glpat-abc123"), false)).doesNotContain("glpat-abc123");
        assertThat(redactor.isSecretHeader("Private-Token")).isTrue();
    }

    @Test
    void xbox_userAuthenticate_rpsTicket_isMasked() {
        String out = json("{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                + "\"RpsTicket\":\"d=EwA-msa-access\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}");
        assertThat(out).doesNotContain("EwA-msa-access").contains("\"RpsTicket\":\"[masqué]\"")
                .contains("\"TokenType\":\"JWT\"").contains("\"AuthMethod\":\"RPS\"");
    }

    @Test
    void xbox_xstsAuthorize_userTokens_isMasked() {
        String out = json("{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"xbl-user-token\"]},"
                + "\"RelyingParty\":\"http://xboxlive.com\",\"TokenType\":\"JWT\"}");
        assertThat(out).doesNotContain("xbl-user-token").contains("\"SandboxId\":\"RETAIL\"");
    }

    @Test
    void xbox_tokenResponses_tokenIsMasked_userHashKept() {
        String out = json("{\"IssueInstant\":\"2026-10-24T10:00:00Z\",\"Token\":\"xsts-token\","
                + "\"DisplayClaims\":{\"xui\":[{\"uhs\":\"1234\"}]}}");
        assertThat(out).doesNotContain("xsts-token").contains("\"uhs\":\"1234\"");
    }

    @Test
    void xbox_xblAuthorizationHeader_isMasked() {
        assertThat(redactor.headers(header("Authorization", "XBL3.0 x=1234;xsts-token"), false))
                .isEqualTo("Authorization: [masqué]");
    }

    @Test
    void minecraft_loginWithXbox_identityToken_isMasked() {
        String out = json("{\"identityToken\":\"XBL3.0 x=1234;xsts-token\"}");
        assertThat(out).doesNotContain("xsts-token").contains("\"identityToken\":\"[masqué]\"");
        assertThat(json("{\"username\":\"u\",\"access_token\":\"mc-access\",\"token_type\":\"Bearer\",\"expires_in\":86400}"))
                .doesNotContain("mc-access").contains("\"token_type\":\"Bearer\"").contains("\"expires_in\":86400");
    }

    @Test
    void msa_deviceCodeStart_deviceAndUserCodes_areMasked() {
        String out = json("{\"device_code\":\"DAQ-dc\",\"user_code\":\"ABCD-EFGH\","
                + "\"verification_uri\":\"https://www.microsoft.com/link\",\"interval\":5}");
        assertThat(out).doesNotContain("DAQ-dc", "ABCD-EFGH").contains("https://www.microsoft.com/link");
    }

    @Test
    void msa_tokenForms_andResponse_areMasked() {
        assertThat(form("client_id=cid&grant_type=refresh_token&refresh_token=M.R3_msa&scope=XboxLive.signin"))
                .isEqualTo("client_id=cid&grant_type=refresh_token&refresh_token=[masqué]&scope=XboxLive.signin");
        assertThat(form("client_id=cid&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=DAQ-dc"))
                .doesNotContain("DAQ-dc");
        assertThat(json("{\"token_type\":\"bearer\",\"access_token\":\"EwA\",\"refresh_token\":\"M.R3\",\"id_token\":\"eyJ\"}"))
                .doesNotContain("\"EwA\"", "M.R3", "eyJ").contains("\"token_type\":\"bearer\"");
    }

    @Test
    void twitch_refreshForm_andClientCredentialsUrl_areMasked() {
        assertThat(form("grant_type=refresh_token&refresh_token=rt&client_id=cid&client_secret=cs"))
                .isEqualTo("grant_type=refresh_token&refresh_token=[masqué]&client_id=cid&client_secret=[masqué]");
        assertThat(redactor.url(URI.create("https://id.twitch.tv/oauth2/token?client_id=cid&client_secret=cs"
                + "&grant_type=client_credentials"), false))
                .isEqualTo("https://id.twitch.tv/oauth2/token?client_id=cid&client_secret=[masqué]&grant_type=client_credentials");
        assertThat(redactor.headers(header("Authorization", "Bearer twitch-user-token"), false))
                .doesNotContain("twitch-user-token");
        assertThat(redactor.headers(header("Client-Id", "cid"), false)).isEqualTo("Client-Id: cid");
    }

    @Test
    void igdb_bearerHeader_isMasked() {
        HttpHeaders h = header("Client-ID", "cid");
        h.add("Authorization", "Bearer app-token");
        assertThat(redactor.headers(h, false)).isEqualTo("Client-ID: cid\nAuthorization: [masqué]");
    }

    @Test
    void steam_webApiKey_andOpenIdSignature_areMasked() {
        assertThat(redactor.url(URI.create("https://api.steampowered.com/IPlayerService/GetOwnedGames/v1/?key=STEAMKEY"
                + "&steamid=1"), false)).doesNotContain("STEAMKEY").endsWith("&steamid=1");
        assertThat(form("openid.mode=check_authentication&openid.signed=signed%2Cop_endpoint&openid.sig=SIG%3D"))
                .isEqualTo("openid.mode=check_authentication&openid.signed=signed%2Cop_endpoint&openid.sig=[masqué]");
    }

    @Test
    void dtdd_apiKeyHeader_isMasked() {
        assertThat(redactor.headers(header("X-API-KEY", "dtdd-key"), false)).isEqualTo("X-API-KEY: [masqué]");
    }

    @Test
    void anyNameCarryingACredential_isMasked_byRule() {
        assertThat(json("{\"client_assertion_secret\":\"a\",\"sessionTicket\":\"b\",\"x_signature\":\"c\","
                + "\"new_password\":\"d\",\"tokenType\":\"JWT\"}"))
                .doesNotContain("\"a\"", "\"b\"", "\"c\"", "\"d\"").contains("\"tokenType\":\"JWT\"");
        assertThat(redactor.headers(header("X-Gitlab-Token", "gl"), false)).isEqualTo("X-Gitlab-Token: [masqué]");
    }

    @Test
    void bodyWithoutContentType_isMaskedAsFreeText() {
        assertThat(redactor.body(b("{\"identityToken\":\"XBL3.0 x=1;t\"}"), null, false)).doesNotContain("x=1;t");
    }

    // ------------------------------------------------- header values and free-text credentials

    @Test
    void headerValues_goThroughFreeTextMasking() {
        HttpHeaders h = header("Location", "https://catapult.test/connect/cb?code=oauth-code&state=s1");
        h.add("Content-Type", "application/json; charset=utf-8");
        assertThat(redactor.headers(h, false))
                .isEqualTo("Location: https://catapult.test/connect/cb?code=[masqué]&state=s1\n"
                        + "Content-Type: application/json; charset=utf-8");
        assertThat(redactor.headers(h, true)).contains("code=oauth-code");
    }

    @Test
    void freeText_bearerAndAuthorizationValues_areMasked() {
        assertThat(redactor.text("401 calling with Bearer abc.def-ghi now", false))
                .isEqualTo("401 calling with Bearer [masqué] now");
        assertThat(redactor.text("request headers:\nAuthorization: XBL3.0 x=12;tok\nAccept: */*", false))
                .isEqualTo("request headers:\nAuthorization: [masqué]\nAccept: */*");
        assertThat(redactor.text("PRIVATE-TOKEN: glpat-xyz", false)).isEqualTo("PRIVATE-TOKEN: [masqué]");
        assertThat(redactor.text("{\"Authorization\":\"Basic dXNlcjpwYXNz\"}", false))
                .doesNotContain("dXNlcjpwYXNz");
        assertThat(redactor.text("identityToken XBL3.0 x=12;tok", false)).doesNotContain("tok\"").doesNotContain(";tok");
        assertThat(redactor.text("HTTP status code: 500, key: name", false))
                .isEqualTo("HTTP status code: 500, key: name");
        assertThat(redactor.text("Bearer abc", true)).isEqualTo("Bearer abc");
    }
}
