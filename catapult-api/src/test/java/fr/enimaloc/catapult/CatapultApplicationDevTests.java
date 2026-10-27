package fr.enimaloc.catapult;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.api.ApiDevIgdbController;
import fr.enimaloc.catapult.config.DevAppHeaderFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(FullContextContainers.class)
@ActiveProfiles("dev")
class CatapultApplicationDevTests extends FullContextTestBase {

    @Test
    void loadsTheDevBeans() {
        assertThat(context.getBeansOfType(ApiDevIgdbController.class)).isNotEmpty();
        assertThat(context.getBeansOfType(DevAppHeaderFilter.class)).isNotEmpty();
    }
}
