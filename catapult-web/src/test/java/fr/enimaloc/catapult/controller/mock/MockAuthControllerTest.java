package fr.enimaloc.catapult.controller.mock;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.mock.MockData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = MockAuthController.class)
@Import(WebSecurityConfig.class)
@ActiveProfiles("mock")
class MockAuthControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void rendersTheMockLoginPageWithEveryChoice() throws Exception {
        mvc.perform(get("/oauth2/authorization/twitch"))
                .andExpect(status().isOk())
                .andExpect(view().name("mock/jwt-select"))
                .andExpect(model().attribute("jwts", MockAuthController.QUICK_LOGINS))
                .andExpect(model().attribute("availableCcls", MockData.AVAILABLE_CCLS))
                .andExpect(model().attribute("availableTws", MockData.AVAILABLE_TWS))
                .andExpect(model().attribute("bindingStatuses", MockData.BINDING_STATUSES))
                .andExpect(model().attribute("sourceTypes", MockData.SOURCE_TYPES))
                .andExpect(model().attribute("minecraftStatuses", MockData.MINECRAFT_STATUSES))
                .andExpect(content().string(containsString("/auth/callback?code=1")))
                .andExpect(content().string(containsString("Offline account")));
    }
}
