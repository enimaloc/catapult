package fr.enimaloc.catapult.api.error;

import fr.enimaloc.catapult.security.TwitchLoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("h2-test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_version_404;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class VersionNotFoundTest {

    @Autowired WebApplicationContext wac;
    @Autowired RouteVersions routeVersions;
    @MockitoBean TwitchLoginSuccessHandler twitchLoginSuccessHandler;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void aVersion3Route_askedWithoutItsVersion_saysWhichVersionServesIt() throws Exception {
        mvc().perform(get("/api/games/speedrun/o1y9j9v6"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.versions[0]").value("3"))
                .andExpect(jsonPath("$.detail").value(containsString("served in version 3")))
                .andExpect(jsonPath("$.detail").value(containsString("?version=3")));
    }

    @Test
    void anUnknownRoute_keepsThePlainNotFound() throws Exception {
        mvc().perform(get("/api/games/nothing-here/at/all"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.versions").doesNotExist());
    }

    @Test
    void theVersionsOfARoute_onlyWhenAllMatchesAreVersioned() {
        assertThat(routeVersions.of("/api/games/speedrun/o1y9j9v6", "GET")).containsExactly("3");
        assertThat(routeVersions.of("/api/games/speedrun/o1y9j9v6", "DELETE")).isEmpty();
        assertThat(routeVersions.of("/api/nothing", "GET")).isEmpty();
    }

    @Test
    void theVersionToAskFor_isTheLowestOfTheDeclaredOne() {
        assertThat(RouteVersions.lowest("3+")).isEqualTo(3);
        assertThat(RouteVersions.lowest("2")).isEqualTo(2);
        assertThat(RouteVersions.lowest("2.1+")).isEqualTo(2);
        assertThat(RouteVersions.lowest("x")).isEqualTo(Integer.MAX_VALUE);
    }
}
