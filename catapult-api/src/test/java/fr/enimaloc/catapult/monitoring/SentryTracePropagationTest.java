package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.debug.DebugRedactor;
import fr.enimaloc.catapult.service.debug.OtherMemberMasker;
import io.sentry.Sentry;
import io.sentry.spring.boot4.SentryAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** No outgoing call of catapult-api carries Sentry's trace headers to the third party it goes to. */
class SentryTracePropagationTest {

    private static final String PROPAGATION_TARGETS = "sentry.trace-propagation-targets";
    private static final List<String> THIRD_PARTIES = List.of(
            "https://api.twitch.tv/helix/users?id=1",
            "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?steamids=1",
            "https://api.igdb.com/v4/games",
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            "https://api.minecraftservices.com/minecraft/profile",
            "https://www.doesthedogdie.com/media/1",
            "https://git.enimaloc.fr/api/v4/projects/1/issues");

    @AfterEach
    void tearDown() {
        Sentry.close();
    }

    /** The value catapult-api runs with (the test resources have an application.properties of their own). */
    private static String configuredTargets() throws IOException {
        String value = PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/application.properties"))
                .getProperty(PROPAGATION_TARGETS);
        assertThat(value).as(PROPAGATION_TARGETS + " in application.properties").isNotNull();
        return value;
    }

    private static ApplicationContextRunner sentryOn(String... properties) {
        DebugMemberRegistry registry = mock(DebugMemberRegistry.class);
        when(registry.modeOf(any())).thenReturn(Optional.empty());
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SentryAutoConfiguration.class, RestClientAutoConfiguration.class))
                .withUserConfiguration(DebugSentryConfig.class)
                .withBean(DebugMemberRegistry.class, () -> registry)
                .withBean(DebugRedactor.class, DebugRedactor::new)
                .withBean(OtherMemberMasker.class, () -> mock(OtherMemberMasker.class))
                .withPropertyValues("sentry.dsn=https://key@sentry.invalid/1", "sentry.send-client-reports=false")
                .withPropertyValues(properties);
    }

    /** The headers each third party got, through the Boot {@code RestClient.Builder} catapult-api's client uses. */
    private static Map<String, HttpHeaders> callEveryThirdParty(RestClient.Builder builder) {
        List<HttpHeaders> received = new CopyOnWriteArrayList<>();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String url : THIRD_PARTIES) {
            server.expect(requestTo(url)).andExpect(request -> received.add(request.getHeaders()))
                    .andRespond(withSuccess());
        }
        RestClient client = builder.build();
        for (String url : THIRD_PARTIES) {
            client.get().uri(java.net.URI.create(url)).retrieve().toBodilessEntity();
        }
        server.verify();
        Map<String, HttpHeaders> byUrl = new java.util.LinkedHashMap<>();
        for (int i = 0; i < THIRD_PARTIES.size(); i++) {
            byUrl.put(THIRD_PARTIES.get(i), received.get(i));
        }
        return byUrl;
    }

    @Test
    void withTheConfiguredTargets_noThirdPartyGetsSentryTraceOrBaggage() throws IOException {
        sentryOn(PROPAGATION_TARGETS + "=" + configuredTargets()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Sentry.isEnabled()).isTrue();
            callEveryThirdParty(context.getBean(RestClient.Builder.class)).forEach((url, headers) -> {
                assertThat(headers.containsHeader("sentry-trace")).as(url).isFalse();
                assertThat(headers.containsHeader("baggage")).as(url).isFalse();
                assertThat(headers.containsHeader("traceparent")).as(url).isFalse();
            });
        });
    }

    /** The test would see the headers: without the setting, Sentry adds them to every call. */
    @Test
    void withSentrysDefaultTargets_thirdPartiesWouldGetThem() {
        sentryOn().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(callEveryThirdParty(context.getBean(RestClient.Builder.class)).values())
                    .allMatch(headers -> headers.containsHeader("sentry-trace"));
        });
    }
}
