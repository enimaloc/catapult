package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.service.AdminCclService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("mock-web")
// Use a dedicated H2 in-memory DB name ("catapult_prometheus") to avoid shared-state conflicts
// with other @SpringBootTest + @ActiveProfiles("mock-web") contexts (e.g. CatapultApplicationTests).
// Without this isolation, both contexts share the same H2 instance, which causes
// ExperimentSynchronizer stale-entity failures when the second context reuses JPA entities
// already managed by the first context's transaction.
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:catapult_prometheus;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY")
class PrometheusEndpointTest {

    @MockitoBean
    AdminCclService adminCclService;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void meterRegistry_isPrometheusRegistry() {
        assertThat(meterRegistry).isInstanceOf(PrometheusMeterRegistry.class);
    }
}
