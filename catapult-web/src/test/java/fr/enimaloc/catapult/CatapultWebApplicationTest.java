package fr.enimaloc.catapult;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.mock.MockApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole application starts under the mock profile, with its real i18n setup. */
@SpringBootTest
@ActiveProfiles("mock")
class CatapultWebApplicationTest {

    @Autowired ApplicationContext context;
    @Autowired MessageSource messages;

    @Test
    void mockProfile_servesTheMockApi() {
        assertThat(context.getBean(ApiService.class)).isInstanceOf(MockApiService.class);
    }

    @Test
    void messages_expandBrandReferences() {
        assertThat(messages.getMessage("hero.discover", null, Locale.FRENCH)).doesNotContain("#{");
    }
}
