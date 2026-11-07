package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.config.DevAppHeaderFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** The dev profile also starts the dev management child context. */
@ActiveProfiles("dev")
class CatapultWebDevFullContextTests extends WebFullContextTestBase {

    @Test
    void loadsTheDevBeans() throws Exception {
        assertThat(context.getBeansOfType(DevAppHeaderFilter.class)).isNotEmpty();
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
    }
}
