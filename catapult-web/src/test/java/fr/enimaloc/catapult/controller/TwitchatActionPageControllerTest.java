package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TwitchatActionPageController.class)
@Import({ModelFiller.class, WebSecurityConfig.class})
class TwitchatActionPageControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void executedAction_saysSo_andAllowsTwitchatsOrigin() throws Exception {
        when(apiService.executeTwitchatAction("tok")).thenReturn(true);

        mvc.perform(get("/widget/twitchat/action/tok").header("Origin", "https://twitchat.fr"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://twitchat.fr"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-executed=\"true\"")));
    }

    @Test
    void expiredToken_saysSo() throws Exception {
        when(apiService.executeTwitchatAction("old")).thenReturn(false);

        mvc.perform(get("/widget/twitchat/action/old"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-executed=\"false\"")));
    }
}
