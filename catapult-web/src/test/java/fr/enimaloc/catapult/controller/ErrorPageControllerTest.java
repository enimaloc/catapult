package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.security.WebSecurityConfig;
import fr.enimaloc.catapult.service.ApiService;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = ErrorPageController.class)
@Import({ModelFiller.class, WebSecurityConfig.class})
class ErrorPageControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApiService apiService;

    @Test
    void fullPageError_rendersTheIndexWithTheErrorPage() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.FORWARD_REQUEST_URI, "/nope"))
                .andExpect(view().name("index"))
                .andExpect(model().attribute("error", true))
                .andExpect(model().attribute("errorCode", 404))
                .andExpect(model().attribute("errorTitle", "error.404.title"))
                .andExpect(model().attributeExists("app", "titles"));
    }

    @Test
    void failedFragmentRequest_rendersOnlyTheErrorFragment() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.FORWARD_REQUEST_URI, "/spa/channel/enimaloc"))
                .andExpect(view().name("pages/error :: error"))
                .andExpect(model().attribute("errorCode", 500))
                .andExpect(model().attributeDoesNotExist("app"));
    }

    @Test
    void errorWithoutStatus_isAServerError() throws Exception {
        mvc.perform(get("/error"))
                .andExpect(view().name("index"))
                .andExpect(model().attribute("errorCode", 500));
    }
}
