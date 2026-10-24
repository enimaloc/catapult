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
}
