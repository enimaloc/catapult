package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import fr.enimaloc.catapult.service.twitch.EventSubService;
import fr.enimaloc.catapult.service.twitch.EventSubTwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchEventSubService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import fr.enimaloc.catapult.service.twitch.TwitchServiceImpl;
import io.sentry.Sentry;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Import(FullContextContainers.class)
class CatapultApplicationTests extends FullContextTestBase {

    @Test
    void runsTheProductionBeanSet() {
        assertThat(context.getBean(TwitchService.class)).isInstanceOf(TwitchServiceImpl.class);
        assertThat(context.getBean(TwitchChatService.class)).isInstanceOf(EventSubTwitchChatService.class);
        assertThat(context.getBean(EventSubService.class)).isInstanceOf(TwitchEventSubService.class);
        assertThat(context.containsBean("customBindingsApiSecurityFilterChain")).isTrue();
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
}
