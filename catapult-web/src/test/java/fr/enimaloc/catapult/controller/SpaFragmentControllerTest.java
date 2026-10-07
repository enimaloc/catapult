package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.ChannelListResponse;
import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** The fragments spa.js fetches on client-side navigation (the channel one is in IndexControllerTest). */
@WebMvcTest(controllers = SpaFragmentController.class)
@Import({ModelFiller.class, WebSecurityConfig.class})
class SpaFragmentControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void landing() throws Exception {
        mvc.perform(get("/spa/landing"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/landing :: landing"))
                .andExpect(model().attribute("page", ""))
                .andExpect(model().attributeExists("app"));
    }

    @Test
    void privacy_inTheRequestedLanguage() throws Exception {
        mvc.perform(get("/spa/privacy").locale(Locale.FRENCH))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/privacy :: privacy"))
                .andExpect(model().attributeExists("privacy", "lastUpdate"));
    }

    @Test
    void channels() throws Exception {
        when(apiService.channelList()).thenReturn(new ChannelListResponse("1", List.of()));

        mvc.perform(get("/spa/channels"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/channels :: channels"))
                .andExpect(model().attribute("channels", List.of()));
    }

    @Test
    void unknownFragment_isA404ErrorFragment() throws Exception {
        mvc.perform(get("/spa/nope"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("pages/error :: error"))
                .andExpect(model().attribute("errorCode", 404));
    }
}
