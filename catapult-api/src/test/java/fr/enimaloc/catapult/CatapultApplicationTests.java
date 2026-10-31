package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import fr.enimaloc.catapult.service.feedback.GitLabClient;
import fr.enimaloc.catapult.service.twitch.EventSubService;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import io.sentry.Sentry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestPropertySource;

@Import(FullContextContainers.class)
class CatapultApplicationTests extends FullContextTestBase {

    @LocalServerPort
    int port;

    @Test
    void runsTheProductionBeanSet() {
        assertThat(context.getBean(TwitchService.class)).isInstanceOf(TwitchServiceImpl.class);
        assertThat(context.getBean(TwitchChatService.class)).isInstanceOf(EventSubTwitchChatService.class);
        assertThat(context.getBean(EventSubService.class)).isInstanceOf(TwitchEventSubService.class);
        assertThat(context.containsBean("publicMeApiSecurityFilterChain")).isTrue();
        assertThat(context.containsBean("securityFilterChain")).isTrue();
        assertThat(environment.getProperty("app.chat.provider")).isEqualTo("eventsub");
    }

    @Test
    void connectsOnlyToItsContainers() {
        assertThat(((HikariDataSource) context.getBean(DataSource.class)).getJdbcUrl()).doesNotContain(":5432/");
        assertThat(((LettuceConnectionFactory) context.getBean(RedisConnectionFactory.class)).getPort())
                .isNotEqualTo(6379);
    }

    @Test
    void startsWithoutOutboundCredentials() {
        assertThat(environment.getProperty("twitch.client-secret")).isEmpty();
        assertThat(environment.getProperty("app.igdb.client-id")).isEmpty();
        assertThat(Sentry.isEnabled()).isFalse();
    }

    @Test
    void isHealthy() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void noPerfBeanOutsideThePerfProfile() throws Exception {
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                        .map(context::getType)
                        .filter(Objects::nonNull)
                        .map(Class::getName))
                .noneMatch(name -> name.startsWith("fr.enimaloc.catapult.perf."));
        assertThat(perfLogin(null)).isIn(401, 404);
        assertThat(perfLogin("203.0.113.7")).isIn(401, 404);
    }

    private int perfLogin(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/internal/perf/login?username=x"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Nested
    @TestPropertySource(properties = "gitlab.token=false")
    class WithGitLabTokenFalse {

        @Test
        void startsAndRegistersGitLabClient() {
            assertThat(context.getBean(GitLabClient.class)).isNotNull();
        }
    }
}
