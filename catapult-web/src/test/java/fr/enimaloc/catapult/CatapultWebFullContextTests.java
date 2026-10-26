package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.perf.PerfLoginController;
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

    /** The perf test login is not there outside the perf profile: nothing answers it, no session is opened. */
    @Test
    void noPerfRouteOutsideThePerfProfile() throws Exception {
        assertThat(context.getBeanNamesForType(PerfLoginController.class)).isEmpty();
        var answer = get(port, "/perf/login?username=x");
        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
    }
}
