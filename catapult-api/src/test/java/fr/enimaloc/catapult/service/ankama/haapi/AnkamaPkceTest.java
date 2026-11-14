package fr.enimaloc.catapult.service.ankama.haapi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnkamaPkceTest {

    @Test
    void challenge_isTheBase64UrlSha256OfTheVerifier() throws Exception {
        AnkamaPkce.Pair pair = AnkamaPkce.generate();

        byte[] digest = MessageDigest.getInstance("SHA-256").digest(pair.verifier().getBytes(StandardCharsets.US_ASCII));
        assertThat(pair.verifier()).hasSize(64).matches("[A-Za-z0-9\\-._~]+");
        assertThat(pair.challenge()).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        assertThat(AnkamaPkce.generate().verifier()).isNotEqualTo(pair.verifier());
    }

    @Test
    void authorizeUrl_hasTheLauncherParameters() {
        assertThat(AnkamaPkce.authorizeUrl("chal"))
                .startsWith("https://auth.ankama.com/login/ankama?")
                .contains("code_challenge=chal", "client_id=102", "direct=true",
                        "redirect_uri=http://127.0.0.1:9001/authorized");
    }

    @Test
    void codeFrom_readsTheRedirectUrl() {
        assertThat(AnkamaPkce.codeFrom("http://127.0.0.1:9001/authorized?code=abc&state=x")).isEqualTo("abc");
    }

    @Test
    void codeFrom_refusesAnErrorRedirect_aMissingCode_andAnotherUrl() {
        assertThatThrownBy(() -> AnkamaPkce.codeFrom("http://127.0.0.1:9001/authorized?error=access_denied"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("no authorization code in the pasted URL");
        assertThatThrownBy(() -> AnkamaPkce.codeFrom("http://127.0.0.1:9001/authorized"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("no authorization code in the pasted URL");
        assertThatThrownBy(() -> AnkamaPkce.codeFrom("https://evil.example/authorized?code=abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("not the launcher redirect URL");
    }
}
