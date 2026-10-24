package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.real.RealApiService;
import io.sentry.Sentry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class CatapultWebFullContextTests extends WebFullContextTestBase {

    @Test
    void runsTheProductionBeanSet() {
        assertThat(context.getBean(ApiService.class)).isInstanceOf(RealApiService.class);
        assertThat(context.containsBean("apiRelayController")).isTrue();
        assertThat(context.getBean(LettuceConnectionFactory.class).getPort()).isNotEqualTo(6379);
        assertThat(Sentry.isEnabled()).isFalse();
    }

    @Test
    void isUp() throws Exception {
        assertThat(get(port, "/up").statusCode()).isEqualTo(200);
        var health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
}
