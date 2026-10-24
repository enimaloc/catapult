package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.debug.DebugMembers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.support.StaticApplicationContext;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The browser's Sentry config: none without a DSN, else the server's own, naming the member. */
class SentryBrowserTest {
    private static final UUID MEMBER = UUID.randomUUID();

    private static ObjectProvider<ApiService> api(UUID member) {
        ApiService api = mock(ApiService.class);
        when(api.currentUserId()).thenReturn(member);
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("api", api);
        return context.getBeanProvider(ApiService.class);
    }

    private static Optional<BuildProperties> build(String commit) {
        Properties properties = new Properties();
        properties.setProperty("git.commit", commit);
        return Optional.of(new BuildProperties(properties));
    }

    @Test
    void noneWithoutADsn() {
        assertThat(new SentryBrowser("", "production", 1.0, 0, build("abc"), api(MEMBER), new StaticApplicationContext().getBeanProvider(DebugMembers.class)).config()).isNull();
    }

    @Test
    void theServersDsnEnvironmentAndRelease_andTheMembersAccount() {
        SentryBrowser.Config config = new SentryBrowser("https://key@sentry.test/1", "staging", 0.2, 1.0, build("abc"), api(MEMBER), new StaticApplicationContext().getBeanProvider(DebugMembers.class)).config();

        assertThat(config.script()).containsEntry("dsn", "https://key@sentry.test/1")
                .containsEntry("environment", "staging")
                .containsEntry("release", "catapult-web@abc")
                .containsEntry("tracesSampleRate", 0.2)
                .containsEntry("replaysSessionSampleRate", 1.0)
                .containsEntry("user", MEMBER.toString());
        assertThat(config.sdk()).isSameAs(SentryBrowser.SDK);
    }

    @Test
    void noReleaseFromAnUnknownCommit_andNoOneWhenLoggedOut() {
        SentryBrowser.Config config = new SentryBrowser("https://key@sentry.test/1", "production", 1.0, 0, build("unknown"), api(null), new StaticApplicationContext().getBeanProvider(DebugMembers.class)).config();

        assertThat(config.script()).containsEntry("release", null).containsEntry("user", null);
    }

    @Test
    void theSdkIsServedFromHere_withTheHashesTheScriptTagsCheck() throws Exception {
        assertThat(SentryBrowser.SDK).isNotEmpty();
        for (Map.Entry<String, String> script : SentryBrowser.SDK.entrySet()) {
            try (InputStream sdk = getClass().getResourceAsStream("/static" + script.getKey())) {
                assertThat(sdk).as("static" + script.getKey()).isNotNull();
                byte[] hash = MessageDigest.getInstance("SHA-384").digest(sdk.readAllBytes());

                assertThat("sha384-" + Base64.getEncoder().encodeToString(hash)).as(script.getKey()).isEqualTo(script.getValue());
            }
        }
    }
}
